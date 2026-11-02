package de.sofahub.app.core.model

/** Fehlerarten der HA-Anbindung. Logik verzweigt nur über diese Typen, nie über Fehlertexte. */
sealed interface HaError {
    data object InvalidUrl : HaError
    /** `.local`-Name (mDNS) – kann Fire OS 8 nicht auflösen. */
    data object LocalNameUnsupported : HaError
    data object HttpNotConfirmed : HaError
    data object Network : HaError
    data object Timeout : HaError
    data object Certificate : HaError
    data object Unauthorized : HaError
    data object Forbidden : HaError
    data object Redirect : HaError
    data object NotFound : HaError
    data class Server(val code: Int) : HaError
    data object InvalidResponse : HaError
    data object EntityUnavailable : HaError
    data object NotConnected : HaError
}

/** Bereinigter Fehlercode für Diagnose-Anzeige: keine URLs, Tokens oder Hostnamen. */
fun HaError.code(): String = when (this) {
    HaError.InvalidUrl -> "HA_URL"
    HaError.LocalNameUnsupported -> "HA_LOCAL_NAME"
    HaError.HttpNotConfirmed -> "HA_HTTP_UNCONFIRMED"
    HaError.Network -> "HA_NETWORK"
    HaError.Timeout -> "HA_TIMEOUT"
    HaError.Certificate -> "HA_CERT"
    HaError.Unauthorized -> "HA_401"
    HaError.Forbidden -> "HA_403"
    HaError.Redirect -> "HA_REDIRECT"
    HaError.NotFound -> "HA_404"
    is HaError.Server -> "HA_HTTP_$code"
    HaError.InvalidResponse -> "HA_RESPONSE"
    HaError.EntityUnavailable -> "HA_ENTITY_UNAVAILABLE"
    HaError.NotConnected -> "HA_OFFLINE"
}

fun HaError.message(): String = when (this) {
    HaError.InvalidUrl -> "Die Adresse ist ungültig. Beispiel: http://192.168.178.20:8123"
    HaError.LocalNameUnsupported -> "Fire TV kann Namen mit .local (z. B. homeassistant.local) nicht auflösen. Bitte die IP-Adresse verwenden, z. B. http://192.168.178.20:8123 – oder eine gefundene Instanz wählen."
    HaError.HttpNotConfirmed -> "Unverschlüsseltes http muss ausdrücklich bestätigt werden."
    HaError.Network -> "Home Assistant ist nicht erreichbar. Adresse und WLAN prüfen."
    HaError.Timeout -> "Home Assistant antwortet nicht rechtzeitig."
    HaError.Certificate -> "Das Zertifikat von Home Assistant ist ungültig oder nicht vertrauenswürdig."
    HaError.Unauthorized -> "Der Zugangstoken ist ungültig oder abgelaufen."
    HaError.Forbidden -> "Der Token hat keine Berechtigung für diese Aktion."
    HaError.Redirect -> "Home Assistant leitet auf eine andere Adresse um. Bitte die endgültige Adresse eintragen."
    HaError.NotFound -> "Unter dieser Adresse wurde keine Home-Assistant-API gefunden."
    is HaError.Server -> "Home Assistant meldet einen Serverfehler ($code)."
    HaError.InvalidResponse -> "Home Assistant hat eine unerwartete Antwort geschickt."
    HaError.EntityUnavailable -> "Das Gerät ist gerade nicht erreichbar."
    HaError.NotConnected -> "Keine Verbindung zu Home Assistant."
}
