package de.sofahub.app.integration.homeassistant

import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.HaError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface HaResult<out T> {
    data class Ok<T>(val value: T) : HaResult<T>
    data class Err(val error: HaError) : HaResult<Nothing>
}

/**
 * REST unter /api/ für Verbindungstest, Initialzustände und Serviceaufrufe. Redirects werden nicht verfolgt, damit der
 * Authorization-Header nie an einen anderen Host geht.
 */
/** Ein Kalendertermin aus Home Assistant; [start]/[end] als ISO-Datum (ganztägig) oder ISO-Zeitpunkt. */
data class CalendarEvent(val calendar: String, val summary: String, val start: String, val end: String, val allDay: Boolean)

class HaRestClient(
    private val base: HttpUrl,
    private val token: String,
    client: OkHttpClient,
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun checkApi(): HaResult<Unit> = get("") { body ->
        val message = (Json.parseToJsonElement(body).jsonObject["message"] as? JsonPrimitive)?.content
        if (message == null) throw IllegalStateException("Antwort ohne message")
    }

    suspend fun states(): HaResult<List<HaEntity>> = get("states") { body -> parseStates(Json.parseToJsonElement(body).jsonArray) }

    suspend fun callService(domain: String, service: String, data: JsonObject): HaResult<Unit> {
        val request = Request.Builder()
            .url(HaUrl.api(base, "services/$domain/$service"))
            .header("Authorization", "Bearer $token")
            .post(data.toString().toRequestBody(JSON))
            .build()
        return execute(request) { }
    }

    /** Zustand einer (eigenen) Entität setzen – HA legt sie bei Bedarf an; nach einem HA-Neustart wird neu gemeldet. */
    suspend fun postState(entityId: String, state: String, attributes: JsonObject): HaResult<Unit> {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("state", JsonPrimitive(state))
            put("attributes", attributes)
        }
        val request = Request.Builder()
            .url(HaUrl.api(base, "states/$entityId"))
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return execute(request) { }
    }

    /** Zahlenverlauf einer Entität ab [start] (ISO-8601): Paare aus Zeitpunkt (ms) und Wert. */
    suspend fun history(entityId: String, start: String): HaResult<List<Pair<Long, Float>>> {
        val url = HaUrl.api(base, "history/period/$start").newBuilder()
            .addQueryParameter("filter_entity_id", entityId)
            .addQueryParameter("minimal_response", null)
            .addQueryParameter("no_attributes", null)
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        return execute(request) { body -> parseHistory(Json.parseToJsonElement(body).jsonArray) }
    }

    /** Termine eines Kalenders im Zeitraum [start, end) (ISO-8601). */
    suspend fun calendarEvents(entityId: String, start: String, end: String): HaResult<List<CalendarEvent>> {
        val url = HaUrl.api(base, "calendars/$entityId").newBuilder().addQueryParameter("start", start).addQueryParameter("end", end).build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        return execute(request) { body -> parseEvents(entityId, Json.parseToJsonElement(body).jsonArray) }
    }

    /** Aktuelles Standbild einer Kamera (JPEG) über den Kamera-Proxy von Home Assistant. */
    suspend fun cameraImage(entityId: String): HaResult<ByteArray> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(HaUrl.api(base, "camera_proxy/$entityId")).header("Authorization", "Bearer $token").get().build()
        try {
            http.newCall(request).await().use { response ->
                mapStatus(response.code)?.let { return@withContext HaResult.Err(it) }
                val bytes = response.body.bytes()
                if (bytes.isEmpty() || bytes.size > MAX_CAMERA_BYTES) HaResult.Err(HaError.InvalidResponse) else HaResult.Ok(bytes)
            }
        } catch (e: IOException) {
            HaResult.Err(mapException(e))
        }
    }

    private suspend fun <T> get(path: String, parse: (String) -> T): HaResult<T> {
        val url = if (path.isEmpty()) HaUrl.api(base).newBuilder().addPathSegment("").build() else HaUrl.api(base, path)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        return execute(request, parse)
    }

    private suspend fun <T> execute(request: Request, parse: (String) -> T): HaResult<T> = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).await().use { response ->
                val error = mapStatus(response.code)
                if (error != null) return@withContext HaResult.Err(error)
                val body = response.body.string()
                try {
                    HaResult.Ok(parse(body))
                } catch (_: Exception) {
                    HaResult.Err(HaError.InvalidResponse)
                }
            }
        } catch (e: IOException) {
            HaResult.Err(mapException(e))
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val MAX_CAMERA_BYTES = 8 * 1024 * 1024

        fun mapStatus(code: Int): HaError? = when (code) {
            in 200..299 -> null
            in 300..399 -> HaError.Redirect
            401 -> HaError.Unauthorized
            403 -> HaError.Forbidden
            404 -> HaError.NotFound
            else -> HaError.Server(code)
        }

        fun mapException(e: Throwable): HaError = when (e) {
            is SSLPeerUnverifiedException, is SSLHandshakeException -> HaError.Certificate
            is SSLException -> if (e.cause is CertificateException) HaError.Certificate else HaError.Network
            is SocketTimeoutException -> HaError.Timeout
            is InterruptedIOException -> HaError.Timeout
            is UnknownHostException -> HaError.Network
            else -> if (e.cause is CertificateException) HaError.Certificate else HaError.Network
        }

        fun parseStates(array: JsonArray): List<HaEntity> = array.mapNotNull { parseState(it) }

        fun parseHistory(array: JsonArray): List<Pair<Long, Float>> {
            val series = array.firstOrNull() as? JsonArray ?: return emptyList()
            return series.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val v = (o["state"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: return@mapNotNull null
                val t = (o["last_changed"] as? JsonPrimitive)?.content ?: (o["last_updated"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                runCatching { java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()?.let { it to v }
            }
        }

        fun parseEvents(calendar: String, array: JsonArray): List<CalendarEvent> = array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            fun time(key: String): Pair<String, Boolean>? {
                val t = obj[key] as? JsonObject ?: return null
                (t["dateTime"] as? JsonPrimitive)?.content?.let { return it to false }
                return (t["date"] as? JsonPrimitive)?.content?.let { it to true }
            }
            val (start, allDay) = time("start") ?: return@mapNotNull null
            val end = time("end")?.first ?: start
            CalendarEvent(calendar, (obj["summary"] as? JsonPrimitive)?.content?.trim().orEmpty().ifEmpty { "Termin" }, start, end, allDay)
        }

        fun parseState(element: JsonElement?): HaEntity? {
            val obj = element as? JsonObject ?: return null
            val id = (obj["entity_id"] as? JsonPrimitive)?.content ?: return null
            if (!id.contains('.')) return null
            return HaEntity(
                entityId = id,
                state = (obj["state"] as? JsonPrimitive)?.content ?: "unknown",
                attributes = obj["attributes"] as? JsonObject ?: JsonObject(emptyMap()),
                lastChanged = (obj["last_changed"] as? JsonPrimitive)?.content ?: "",
                lastUpdated = (obj["last_updated"] as? JsonPrimitive)?.content ?: "",
            )
        }
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}
