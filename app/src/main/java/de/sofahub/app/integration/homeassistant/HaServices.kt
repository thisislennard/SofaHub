package de.sofahub.app.integration.homeassistant

import de.sofahub.app.core.model.Availability
import de.sofahub.app.core.model.HaEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.roundToInt

/** Ein konkreter, dokumentierter HA-Serviceaufruf. */
data class ServiceCall(val domain: String, val service: String, val data: JsonObject) {
    /** Szenen/Skripte sind nicht idempotent: nie automatisch wiederholen. */
    val idempotent: Boolean get() = domain != "scene" && domain != "script"
}

object HaServices {
    private fun target(entityId: String, extra: JsonObject.() -> JsonObject = { this }) =
        buildJsonObject { put("entity_id", entityId) }.extra()

    fun turnOn(entityId: String) = ServiceCall(entityId.substringBefore('.'), "turn_on", target(entityId))
    fun turnOff(entityId: String) = ServiceCall(entityId.substringBefore('.'), "turn_off", target(entityId))

    /** Explizit ein/aus statt toggle: wiederholte Tastendrücke führen nie zu unkontrolliertem Hin- und Herschalten. */
    fun setOn(entityId: String, on: Boolean) = if (on) turnOn(entityId) else turnOff(entityId)

    /** Helligkeit in Prozent -> brightness 0..255. 0 % schaltet aus, sonst mindestens 1. */
    fun setBrightness(entityId: String, percent: Int): ServiceCall {
        val pct = percent.coerceIn(0, 100)
        if (pct == 0) return turnOff(entityId)
        return ServiceCall("light", "turn_on", buildJsonObject {
            put("entity_id", entityId)
            put("brightness", percentToBrightness(pct))
        })
    }

    /** Licht einschalten mit Farbe (RGB, Vorrang) oder Weißton (Kelvin) und Helligkeit in Prozent – alles optional. */
    fun lightOn(entityId: String, rgb: List<Int>?, kelvin: Int?, brightnessPct: Int?) = ServiceCall("light", "turn_on", buildJsonObject {
        put("entity_id", entityId)
        val color = rgb?.takeIf { it.size == 3 }
        if (color != null) put("rgb_color", JsonArray(color.map { JsonPrimitive(it.coerceIn(0, 255)) }))
        else kelvin?.let { put("color_temp_kelvin", it.coerceIn(2000, 6500)) }
        brightnessPct?.let { put("brightness_pct", it.coerceIn(1, 100)) }
    })

    fun activateScene(entityId: String) = ServiceCall("scene", "turn_on", target(entityId))
    fun runScript(entityId: String) = ServiceCall("script", "turn_on", target(entityId))

    fun media(entityId: String, action: MediaAction): ServiceCall = when (action) {
        MediaAction.POWER_ON -> ServiceCall("media_player", "turn_on", target(entityId))
        MediaAction.POWER_OFF -> ServiceCall("media_player", "turn_off", target(entityId))
        MediaAction.VOLUME_UP -> ServiceCall("media_player", "volume_up", target(entityId))
        MediaAction.VOLUME_DOWN -> ServiceCall("media_player", "volume_down", target(entityId))
        MediaAction.MUTE -> ServiceCall("media_player", "volume_mute", buildJsonObject { put("entity_id", entityId); put("is_volume_muted", true) })
        MediaAction.UNMUTE -> ServiceCall("media_player", "volume_mute", buildJsonObject { put("entity_id", entityId); put("is_volume_muted", false) })
    }

    fun selectSource(entityId: String, source: String) =
        ServiceCall("media_player", "select_source", buildJsonObject { put("entity_id", entityId); put("source", source) })

    fun percentToBrightness(percent: Int): Int = (percent.coerceIn(0, 100) * 255 / 100.0).roundToInt().coerceIn(1, 255)
    fun brightnessToPercent(brightness: Long): Int = (brightness.coerceIn(0, 255) * 100 / 255.0).roundToInt()
}

enum class MediaAction { POWER_ON, POWER_OFF, VOLUME_UP, VOLUME_DOWN, MUTE, UNMUTE }

/** Fähigkeiten und Anzeige einer Entität, nur aus gemeldeten Attributen abgeleitet (nichts aus Namen geraten). */
object EntityView {
    // media_player supported_features (homeassistant.components.media_player.MediaPlayerEntityFeature)
    private const val MP_VOLUME_SET = 4L
    private const val MP_VOLUME_MUTE = 8L
    private const val MP_TURN_ON = 128L
    private const val MP_TURN_OFF = 256L
    private const val MP_VOLUME_STEP = 1024L
    private const val MP_SELECT_SOURCE = 2048L

    val SWITCHABLE = setOf("light", "switch", "input_boolean", "fan")
    val WINDOW_CLASSES = setOf("window", "door", "garage_door", "opening")

    fun isOn(e: HaEntity) = e.state == "on"

    fun isSwitchable(e: HaEntity) = e.domain in SWITCHABLE

    fun isDimmable(e: HaEntity): Boolean {
        if (e.domain != "light") return false
        val modes = e.attrStringList("supported_color_modes")
        if (modes.isNotEmpty()) return modes.any { it != "onoff" }
        return (e.attrLong("supported_features") ?: 0L) and 1L != 0L
    }

    /** Aktuelle Helligkeit in Prozent oder null, wenn aus/unbekannt. */
    fun brightnessPercent(e: HaEntity): Int? = if (isOn(e)) e.attrLong("brightness")?.let { HaServices.brightnessToPercent(it) } else null

    fun isWindowSensor(e: HaEntity) = e.domain == "binary_sensor" && e.attrString("device_class") in WINDOW_CLASSES

    fun isTemperature(e: HaEntity) = when (e.domain) {
        "climate" -> true
        "sensor" -> e.attrString("device_class") == "temperature"
        else -> false
    }

    fun mediaSupports(e: HaEntity): Set<MediaAction> {
        val f = e.attrLong("supported_features") ?: 0L
        val out = mutableSetOf<MediaAction>()
        if (f and MP_TURN_ON != 0L) out += MediaAction.POWER_ON
        if (f and MP_TURN_OFF != 0L) out += MediaAction.POWER_OFF
        if (f and (MP_VOLUME_STEP or MP_VOLUME_SET) != 0L) { out += MediaAction.VOLUME_UP; out += MediaAction.VOLUME_DOWN }
        if (f and MP_VOLUME_MUTE != 0L) { out += MediaAction.MUTE; out += MediaAction.UNMUTE }
        return out
    }

    fun mediaSources(e: HaEntity): List<String> =
        if ((e.attrLong("supported_features") ?: 0L) and MP_SELECT_SOURCE != 0L) e.attrStringList("source_list") else emptyList()

    fun formatNumber(value: Double, decimals: Int = 1): String = String.format(Locale.GERMANY, "%.${decimals}f", value)

    /** Temperatur mit Einheit aus der Entität; Klima-Entitäten liefern current_temperature. */
    fun temperatureText(e: HaEntity): String? {
        if (e.availability != Availability.NORMAL && e.domain != "climate") return null
        val value = when (e.domain) {
            "climate" -> e.attrDouble("current_temperature")
            else -> e.state.toDoubleOrNull()
        } ?: return null
        val unit = e.attrString("unit_of_measurement") ?: e.attrString("temperature_unit") ?: "°"
        return formatNumber(value) + if (unit == "°C" || unit == "°F" || unit == "°") "°" else " $unit"
    }

    fun climateDetail(e: HaEntity): String? {
        if (e.domain != "climate") return null
        val target = e.attrDouble("temperature")?.let { "Ziel ${formatNumber(it)}°" }
        val action = when (e.attrString("hvac_action")) {
            "heating" -> "heizt"
            "cooling" -> "kühlt"
            "idle" -> "Leerlauf"
            "off" -> "aus"
            else -> null
        }
        return listOfNotNull(target, action).joinToString(" · ").ifEmpty { null }
    }

    /** Sensorwert mit Einheit. */
    fun sensorText(e: HaEntity): String? {
        if (e.availability != Availability.NORMAL) return null
        val unit = e.attrString("unit_of_measurement")
        val number = e.state.toDoubleOrNull()
        val value = number?.let { if (it % 1.0 == 0.0) it.toLong().toString() else formatNumber(it) } ?: e.state
        return if (unit.isNullOrEmpty()) value else if (unit == "%") "$value %" else "$value $unit"
    }

    fun weatherCondition(state: String): String = when (state) {
        "clear-night" -> "Klar"
        "cloudy" -> "Bedeckt"
        "exceptional" -> "Unwetter"
        "fog" -> "Nebel"
        "hail" -> "Hagel"
        "lightning" -> "Gewitter"
        "lightning-rainy" -> "Gewitter, Regen"
        "partlycloudy" -> "Teils bewölkt"
        "pouring" -> "Starkregen"
        "rainy" -> "Regen"
        "snowy" -> "Schnee"
        "snowy-rainy" -> "Schneeregen"
        "sunny" -> "Sonnig"
        "windy" -> "Windig"
        "windy-variant" -> "Windig, bewölkt"
        else -> state
    }

    fun weatherTemperature(e: HaEntity): String? {
        if (e.availability != Availability.NORMAL) return null
        val t = e.attrDouble("temperature") ?: return null
        return "${t.roundToInt()}°"
    }

    /** Kurzer Zustandstext, z. B. "An · 80 %", "Aus", "Nicht erreichbar". */
    fun stateLabel(e: HaEntity): String {
        if (e.availability == Availability.UNAVAILABLE) return "Nicht erreichbar"
        if (e.availability == Availability.UNKNOWN) return "Unbekannt"
        return when (e.domain) {
            "light" -> if (isOn(e)) brightnessPercent(e)?.let { "An · $it %" } ?: "An" else "Aus"
            "switch", "input_boolean", "fan" -> {
                val power = e.attrDouble("current_power_w")
                if (isOn(e)) power?.let { "An · ${it.roundToInt()} W" } ?: "An" else "Aus"
            }
            "binary_sensor" -> if (isWindowSensor(e)) (if (isOn(e)) "Offen" else "Geschlossen") else (if (isOn(e)) "Ein" else "Aus")
            "climate" -> climateDetail(e) ?: e.state
            "sensor" -> sensorText(e) ?: e.state
            "scene" -> "Szene"
            "script" -> if (e.state == "on") "Läuft" else "Skript"
            "media_player" -> when (e.state) {
                "off" -> "Aus"
                "playing" -> "Wiedergabe"
                "paused" -> "Pausiert"
                "idle", "on" -> "An"
                "standby" -> "Standby"
                else -> e.state
            }
            "weather" -> weatherCondition(e.state)
            else -> e.state
        }
    }
}
