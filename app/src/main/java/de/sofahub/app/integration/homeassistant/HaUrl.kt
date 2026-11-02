package de.sofahub.app.integration.homeassistant

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface HaUrlResult {
    data class Valid(val url: HttpUrl, val normalized: String, val isHttp: Boolean) : HaUrlResult
    data class Invalid(val reason: String) : HaUrlResult
}

/** Prüft und normalisiert die eingegebene HA-Basisadresse. */
object HaUrl {
    fun parse(input: String): HaUrlResult {
        var text = input.trim()
        if (text.isEmpty()) return HaUrlResult.Invalid("Bitte eine Adresse eingeben.")
        if (text.any { it.isWhitespace() }) return HaUrlResult.Invalid("Die Adresse darf keine Leerzeichen enthalten.")
        val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://").find(text)?.groupValues?.get(1)?.lowercase()
        if (scheme == null) {
            if (text.contains("://")) return HaUrlResult.Invalid("Ungültiges Protokoll.")
            // Ohne Protokoll: lokale Adressen (IP, Port, .local) sprechen bei HA praktisch immer http, Domainnamen https.
            val hostPart = text.substringBefore('/').substringBefore('?')
            val looksLocal = Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?$""").matches(hostPart) || hostPart.contains(':') ||
                hostPart.substringBefore(':').endsWith(".local", ignoreCase = true)
            text = (if (looksLocal) "http://" else "https://") + text
        } else if (scheme != "http" && scheme != "https") {
            return HaUrlResult.Invalid("Nur http:// oder https:// ist möglich.")
        }
        val raw = text.toHttpUrlOrNull() ?: return HaUrlResult.Invalid("Die Adresse ist ungültig.")
        if (raw.username.isNotEmpty() || raw.password.isNotEmpty()) {
            return HaUrlResult.Invalid("Zugangsdaten gehören nicht in die Adresse.")
        }
        if (raw.host.isBlank()) return HaUrlResult.Invalid("Hostname fehlt.")
        if (raw.query != null || raw.fragment != null) return HaUrlResult.Invalid("Bitte nur die Basisadresse ohne ? oder # eingeben.")
        // Pfad-Präfix (Reverse-Proxy) erlauben, aber ein angehängtes /api oder Dashboard-Pfade entfernen.
        val segments = raw.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        val apiIndex = segments.indexOf("api")
        if (apiIndex >= 0) while (segments.size > apiIndex) segments.removeAt(segments.lastIndex)
        val builder = raw.newBuilder().encodedPath("/")
        segments.forEach { builder.addPathSegment(it) }
        val url = builder.build()
        val normalized = url.toString().removeSuffix("/")
        return HaUrlResult.Valid(url, normalized, isHttp = !url.isHttps)
    }

    fun api(base: HttpUrl, vararg path: String): HttpUrl = base.newBuilder().apply {
        addPathSegment("api")
        path.forEach { addPathSegments(it) }
    }.build()

    fun websocket(base: HttpUrl): String {
        val api = api(base, "websocket").toString()
        return if (api.startsWith("https://")) "wss://" + api.removePrefix("https://") else "ws://" + api.removePrefix("http://")
    }
}
