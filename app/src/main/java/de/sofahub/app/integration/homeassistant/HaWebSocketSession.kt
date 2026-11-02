package de.sofahub.app.integration.homeassistant

import de.sofahub.app.core.model.HaArea
import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.HaError
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

sealed interface SessionEvent {
    data object Authenticated : SessionEvent
    data class Snapshot(val entities: Map<String, HaEntity>, val deleted: Set<String>) : SessionEvent
    data class Changed(val entities: Map<String, HaEntity>, val deleted: Set<String>) : SessionEvent
    data class Registry(val areas: Map<String, HaArea>, val entityAreas: Map<String, String>) : SessionEvent
    data object RegistryUnavailable : SessionEvent
}

sealed interface SessionEnd {
    data object AuthInvalid : SessionEnd
    data class Lost(val error: HaError) : SessionEnd
}

/**
 * Eine WebSocket-Verbindung zu /api/websocket: auth_required -> auth -> auth_ok/auth_invalid, dann state_changed
 * abonnieren, danach get_states (Snapshot) und Registry-Abfragen. Request-IDs sind eindeutig pro Verbindung; Antworten
 * werden über die ID zugeordnet. [run] kehrt erst zurück, wenn die Verbindung endet.
 */
class HaWebSocketSession(
    client: OkHttpClient,
    private val url: String,
    private val token: String,
    private val authTimeoutMs: Long = 15_000,
    private val snapshotTimeoutMs: Long = 30_000,
    /** Nur diese Entitäten beobachten (Hintergrund): subscribe_entities statt aller Zustandsänderungen und Registry. */
    private val watch: Set<String>? = null,
) {
    private val http = client.newBuilder().pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val incoming = Channel<Incoming>(Channel.UNLIMITED)
    /** Schon abgeholte, noch nicht verarbeitete Nachricht (Blick voraus, ob weitere warten). */
    private var peeked: Incoming? = null
    private var socket: WebSocket? = null
    private var nextId = 1

    private sealed interface Incoming {
        data class Text(val text: String) : Incoming
        data class Closed(val error: HaError) : Incoming
    }

    /** Beendet die Sitzung sofort; [run] gibt danach den Socket endgültig frei. */
    fun close() {
        socket?.close(1000, null)
        incoming.trySend(Incoming.Closed(HaError.NotConnected))
    }

    suspend fun run(onEvent: suspend (SessionEvent) -> Unit): SessionEnd {
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.trySend(Incoming.Text(text))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = response?.code?.let { HaRestClient.mapStatus(it) } ?: HaRestClient.mapException(t)
                incoming.trySend(Incoming.Closed(error))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                incoming.trySend(Incoming.Closed(HaError.Network))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        })
        try {
            return session(onEvent)
        } finally {
            socket?.cancel()
            socket = null
        }
    }

    private suspend fun session(onEvent: suspend (SessionEvent) -> Unit): SessionEnd {
        // 1. Authentifizierung
        try {
            withTimeout(authTimeoutMs) {
                expectType("auth_required")
                send(buildJsonObject { put("type", "auth"); put("access_token", token) })
            }
            val result = withTimeout(authTimeoutMs) { nextMessage() }
            when (result?.type()) {
                "auth_ok" -> Unit
                "auth_invalid" -> return SessionEnd.AuthInvalid
                null -> return SessionEnd.Lost(lastError)
                else -> return SessionEnd.Lost(HaError.InvalidResponse)
            }
        } catch (_: TimeoutCancellationException) {
            return SessionEnd.Lost(HaError.Timeout)
        } catch (_: ProtocolException) {
            return SessionEnd.Lost(lastError)
        }
        onEvent(SessionEvent.Authenticated)
        watch?.let { return watchSession(it, onEvent) }

        // 2. Subscription vor Snapshot, damit keine Änderung dazwischen verloren geht.
        val subscribeId = sendCommand("subscribe_events") { put("event_type", "state_changed") }
        val statesId = sendCommand("get_states")
        val areaId = sendCommand("config/area_registry/list")
        val entityRegId = sendCommand("config/entity_registry/list")
        val deviceRegId = sendCommand("config/device_registry/list")

        val merger = StateMerger()
        var areas: Map<String, HaArea>? = null
        var entityReg: JsonArray? = null
        var deviceReg: JsonArray? = null
        var registryFailed = false
        var registryDone = false
        val snapshotDeadline = System.currentTimeMillis() + snapshotTimeoutMs
        var dirty = false

        while (true) {
            // Schübe zusammenfassen: erst melden, wenn nichts mehr wartet – sonst eine Kopie aller Entitäten pro Änderung.
            if (dirty && !hasPending()) {
                dirty = false
                onEvent(SessionEvent.Changed(merger.entities(), merger.deleted()))
            }
            val msg = if (!merger.hasSnapshot) {
                val remaining = snapshotDeadline - System.currentTimeMillis()
                if (remaining <= 0) return SessionEnd.Lost(HaError.Timeout)
                try {
                    withTimeout(remaining) { nextMessage() }
                } catch (_: TimeoutCancellationException) {
                    return SessionEnd.Lost(HaError.Timeout)
                }
            } else {
                nextMessage()
            } ?: return SessionEnd.Lost(lastError)

            when (msg.type()) {
                "event" -> {
                    if (msg.int("id") != subscribeId) continue
                    val change = parseChange(msg["event"] as? JsonObject) ?: continue
                    if (merger.onEvent(change)) dirty = true
                }
                "result" -> {
                    val id = msg.int("id")
                    val success = (msg["success"] as? JsonPrimitive)?.booleanOrNull == true
                    when (id) {
                        subscribeId -> if (!success) return SessionEnd.Lost(errorOf(msg))
                        statesId -> {
                            if (!success) return SessionEnd.Lost(errorOf(msg))
                            val list = (msg["result"] as? JsonArray)?.let { HaRestClient.parseStates(it) }
                                ?: return SessionEnd.Lost(HaError.InvalidResponse)
                            merger.onSnapshot(list)
                            dirty = false
                            onEvent(SessionEvent.Snapshot(merger.entities(), merger.deleted()))
                        }
                        areaId -> if (success) areas = parseAreas(msg["result"] as? JsonArray) else registryFailed = true
                        entityRegId -> if (success) entityReg = msg["result"] as? JsonArray else registryFailed = true
                        deviceRegId -> if (success) deviceReg = msg["result"] as? JsonArray else registryFailed = true
                    }
                    if (!registryDone && (registryFailed || (areas != null && entityReg != null && deviceReg != null))) {
                        registryDone = true
                        if (registryFailed || areas == null) onEvent(SessionEvent.RegistryUnavailable)
                        else onEvent(SessionEvent.Registry(areas, resolveEntityAreas(entityReg, deviceReg)))
                    }
                }
                "pong" -> Unit
            }
        }
    }

    /** Schlanke Sitzung für den Hintergrund: HA schickt nur Änderungen der gewählten Entitäten. */
    private suspend fun watchSession(ids: Set<String>, onEvent: suspend (SessionEvent) -> Unit): SessionEnd {
        val subscribeId = sendCommand("subscribe_entities") { put("entity_ids", JsonArray(ids.map { JsonPrimitive(it) })) }
        var entities = emptyMap<String, HaEntity>()
        var first = true
        while (true) {
            val msg = nextMessage() ?: return SessionEnd.Lost(lastError)
            when (msg.type()) {
                "event" -> {
                    if (msg.int("id") != subscribeId) continue
                    val event = msg["event"] as? JsonObject ?: continue
                    entities = CompressedStates.apply(entities, event)
                    onEvent(if (first) SessionEvent.Snapshot(entities, emptySet()) else SessionEvent.Changed(entities, emptySet()))
                    first = false
                }
                "result" -> if (msg.int("id") == subscribeId && (msg["success"] as? JsonPrimitive)?.booleanOrNull != true) {
                    return SessionEnd.Lost(errorOf(msg))
                }
            }
        }
    }

    private class ProtocolException : Exception()

    private var lastError: HaError = HaError.Network

    private suspend fun nextMessage(): JsonObject? {
        while (true) {
            val item = peeked?.also { peeked = null } ?: incoming.receive()
            when (item) {
                is Incoming.Closed -> {
                    lastError = item.error
                    return null
                }
                is Incoming.Text -> {
                    val obj = runCatching { Json.parseToJsonElement(item.text).jsonObject }.getOrNull() ?: continue
                    return obj
                }
            }
        }
    }

    private fun hasPending(): Boolean {
        if (peeked == null) peeked = incoming.tryReceive().getOrNull()
        return peeked != null
    }

    private suspend fun expectType(type: String) {
        val msg = nextMessage() ?: throw ProtocolException()
        if (msg.type() != type) {
            lastError = HaError.InvalidResponse
            throw ProtocolException()
        }
    }

    private fun send(obj: JsonObject) {
        socket?.send(obj.toString())
    }

    private fun sendCommand(type: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): Int {
        val id = nextId++
        send(buildJsonObject {
            put("id", id)
            put("type", type)
            extra()
        })
        return id
    }

    companion object {
        private fun JsonObject.type(): String? = (this["type"] as? JsonPrimitive)?.content
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

        private fun errorOf(msg: JsonObject): HaError {
            val code = ((msg["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.content
            return if (code == "unauthorized") HaError.Forbidden else HaError.InvalidResponse
        }

        fun parseChange(event: JsonObject?): StateMerger.Change? {
            val data = event?.get("data") as? JsonObject ?: return null
            val id = (data["entity_id"] as? JsonPrimitive)?.content ?: return null
            val new = HaRestClient.parseState(data["new_state"]?.takeIf { it is JsonObject })
            val oldUpdated = ((data["old_state"] as? JsonObject)?.get("last_updated") as? JsonPrimitive)?.content
            return StateMerger.Change(id, new, oldUpdated)
        }

        fun parseAreas(array: JsonArray?): Map<String, HaArea>? = array?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["area_id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.content ?: id
            id to HaArea(id, name)
        }?.toMap()

        /** Raum einer Entität: eigene area_id, sonst die ihres Geräts. */
        fun resolveEntityAreas(entities: JsonArray?, devices: JsonArray?): Map<String, String> {
            val deviceArea = devices.orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val area = (o["area_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                id to area
            }.toMap()
            return entities.orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = (o["entity_id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val own = (o["area_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val dev = (o["device_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val area = own ?: dev?.let { deviceArea[it] } ?: return@mapNotNull null
                id to area
            }.toMap()
        }

        private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
    }
}
