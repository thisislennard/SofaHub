package de.sofahub.app.integration.updates

import de.sofahub.app.core.network.BlockedRequestException
import de.sofahub.app.integration.homeassistant.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed interface ReleaseFetch {
    data class Found(val release: ReleaseInfo) : ReleaseFetch
    /** 404: kein veröffentlichtes Release – oder das Repository ist privat (ohne Token unsichtbar). */
    data object NoRelease : ReleaseFetch
    data class RateLimited(val resetEpochSeconds: Long?) : ReleaseFetch
    data object Network : ReleaseFetch
    data class Http(val code: Int) : ReleaseFetch
    data object Invalid : ReleaseFetch
    data object Blocked : ReleaseFetch
    /** 401: hinterlegter GitHub-Token ungültig, abgelaufen oder ohne Zugriff. */
    data object Unauthorized : ReleaseFetch
}

sealed interface TokenCheck {
    data object Ok : TokenCheck
    data object Invalid : TokenCheck
    data object NoAccess : TokenCheck
    data object Network : TokenCheck
}

/** Ziel-Adresse und Header für einen Asset-Download (mit Token über die API, sonst öffentlich). */
data class AssetRequest(val url: String, val headers: Map<String, String>)

/**
 * GitHub Releases API. /releases/latest liefert nur veröffentlichte Stable-Releases (keine Drafts/Prereleases); beides wird
 * zusätzlich geprüft. Für ein **privates** Repository braucht die App einen vom Nutzer hinterlegten, nur lesenden
 * Fine-grained-Token ([token]); er steckt nie in der APK und geht ausschließlich an api.github.com – Weiterleitungen zum
 * Download-Speicher bekommen ihn nicht (RedirectInterceptor entfernt den Header).
 */
class GitHubReleaseClient(
    private val repo: String,
    private val userAgent: String,
    private val client: OkHttpClient,
    private val apiBase: HttpUrl = "https://api.github.com/".toHttpUrl(),
    private val token: () -> String? = { null },
) {
    private fun Request.Builder.auth(): Request.Builder = apply {
        token()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
    }

    private fun apiRequest(url: HttpUrl) = Request.Builder().url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", userAgent)
        .auth()
        .build()

    /** Prüft einen neu eingegebenen Token gegen genau dieses Repository, ohne ihn zu speichern. */
    suspend fun checkToken(candidate: String): TokenCheck = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(apiBase.newBuilder().addPathSegments("repos/$repo").build())
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
            .header("Authorization", "Bearer ${candidate.trim()}")
            .build()
        try {
            client.newCall(request).await().use { r ->
                when (r.code) {
                    in 200..299 -> TokenCheck.Ok
                    401 -> TokenCheck.Invalid
                    else -> TokenCheck.NoAccess
                }
            }
        } catch (_: IOException) {
            TokenCheck.Network
        }
    }

    /**
     * Download-Anfrage für ein Asset dieses Releases. Mit Token über die Asset-API (nötig für private Repositories);
     * deren Adresse muss zu genau diesem Repository gehören.
     */
    fun assetRequest(asset: ReleaseAsset): AssetRequest {
        val t = token()?.takeIf { it.isNotBlank() }
        val api = asset.apiUrl
        val prefix = apiBase.newBuilder().addPathSegments("repos/$repo/releases/assets/").build().toString()
        return if (t != null && api != null && api.startsWith(prefix)) {
            AssetRequest(api, mapOf("Authorization" to "Bearer $t", "Accept" to "application/octet-stream", "X-GitHub-Api-Version" to "2022-11-28"))
        } else {
            AssetRequest(asset.downloadUrl, mapOf("Accept" to "application/octet-stream"))
        }
    }

    suspend fun latest(): ReleaseFetch = withContext(Dispatchers.IO) {
        val url = apiBase.newBuilder().addPathSegments("repos/$repo/releases/latest").build()
        val request = apiRequest(url)
        try {
            client.newCall(request).await().use { r ->
                when {
                    r.code == 401 -> ReleaseFetch.Unauthorized
                    r.code == 404 -> ReleaseFetch.NoRelease
                    (r.code == 403 || r.code == 429) && (r.header("x-ratelimit-remaining") == "0" || r.code == 429) ->
                        ReleaseFetch.RateLimited(r.header("x-ratelimit-reset")?.toLongOrNull())
                    !r.isSuccessful -> ReleaseFetch.Http(r.code)
                    else -> {
                        val text = r.body.string()
                        val release = parseRelease(text) ?: return@use ReleaseFetch.Invalid
                        if (release.draft || release.prerelease) ReleaseFetch.NoRelease else ReleaseFetch.Found(release)
                    }
                }
            }
        } catch (_: BlockedRequestException) {
            ReleaseFetch.Blocked
        } catch (_: IOException) {
            ReleaseFetch.Network
        }
    }

    /** Lädt eine kleine Textdatei (update.json) mit Größenlimit. */
    suspend fun downloadText(target: AssetRequest, maxBytes: Long): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(target.url).header("User-Agent", userAgent)
            .apply { target.headers.forEach { (k, v) -> header(k, v) } }.build()
        try {
            client.newCall(request).await().use { r ->
                if (!r.isSuccessful) return@use null
                val body = r.body
                if (body.contentLength() > maxBytes) return@use null
                val source = body.source()
                source.request(maxBytes + 1)
                if (source.buffer.size > maxBytes) return@use null
                source.readByteArray().toString(Charsets.UTF_8)
            }
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        /** Hosts, auf die GitHub Release-Downloads weiterleitet. Alles andere wird blockiert. */
        val ALLOWED_HOSTS = setOf(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com",
        )

        fun parseRelease(text: String): ReleaseInfo? = runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            fun s(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ReleaseInfo(
                tag = s("tag_name") ?: return null,
                name = s("name") ?: "",
                body = s("body") ?: "",
                publishedAt = s("published_at") ?: "",
                draft = (o["draft"] as? JsonPrimitive)?.booleanOrNull ?: false,
                prerelease = (o["prerelease"] as? JsonPrimitive)?.booleanOrNull ?: false,
                assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val a = el as? JsonObject ?: return@mapNotNull null
                    val name = (a["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val size = (a["size"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                    val url = (a["browser_download_url"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val api = (a["url"] as? JsonPrimitive)?.content
                    ReleaseAsset(name, size, url, api)
                },
            )
        }.getOrNull()

        private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
    }
}
