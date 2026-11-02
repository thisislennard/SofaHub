package de.sofahub.app.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class WifiSecurity(val label: String) { WPA("WPA/WPA2/WPA3"), WEP("WEP"), NONE("Offen") }

/**
 * Gäste-WLAN für die QR-Code-Kachel. Inhalt im WLAN-QR-Format (`WIFI:T:WPA;S:…;P:…;;`), das Handykameras direkt als
 * „Mit WLAN verbinden“ erkennen.
 */
@Serializable
data class GuestWifi(
    val ssid: String,
    val password: String = "",
    val security: WifiSecurity = WifiSecurity.WPA,
) {
    fun qrContent(): String = buildString {
        append("WIFI:T:").append(if (security == WifiSecurity.NONE) "nopass" else security.name)
        append(";S:").append(escape(ssid))
        if (security != WifiSecurity.NONE) append(";P:").append(escape(password))
        append(";;")
    }

    /** Fehlertext für die Eingabe, null = in Ordnung. */
    fun problem(): String? = when {
        ssid.isBlank() -> "Bitte den WLAN-Namen eingeben"
        ssid.toByteArray().size > 32 -> "WLAN-Name ist zu lang (höchstens 32 Zeichen)"
        security == WifiSecurity.WPA && password.length !in 8..63 -> "WPA-Passwort muss 8 bis 63 Zeichen haben"
        security == WifiSecurity.WEP && password.isEmpty() -> "Bitte das WEP-Passwort eingeben"
        else -> null
    }

    private companion object {
        /** Sonderzeichen des WLAN-QR-Formats mit Backslash schützen. */
        fun escape(text: String) = text.replace(Regex("""([\\;,:"])"""), """\\$1""")
    }
}
