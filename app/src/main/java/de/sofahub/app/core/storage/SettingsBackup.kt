package de.sofahub.app.core.storage

import de.sofahub.app.core.model.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Sicherung der Einstellungen als JSON-Datei (über die Einrichtungsseite im Browser). Enthält nie Tokens – die liegen
 * verschlüsselt im Keystore. Fotos/Hintergrund (Dateien auf dem Gerät) und gerätebezogener Update-Zustand bleiben außen vor.
 */
object SettingsBackup {
    const val FORMAT = "sofahub-settings"
    const val VERSION = 1
    private val DEVICE_ONLY = setOf("photos", "background", "pendingUpdate", "lastUpdateCheckEpochMs")
    private val pretty = Json { prettyPrint = true }

    fun export(settings: AppSettings, nowEpochMs: Long): String {
        val obj = SettingsCodec.json.parseToJsonElement(SettingsCodec.encode(settings)).jsonObject
        val root = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAtEpochMs", nowEpochMs)
            put("settings", JsonObject(obj - DEVICE_ONLY))
        }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    sealed interface ImportResult {
        data class Ok(val settings: AppSettings) : ImportResult
        data class Invalid(val message: String) : ImportResult
    }

    /** Übernimmt die gesicherten Einstellungen; Fotos, Hintergrund und Update-Zustand dieses Geräts bleiben erhalten. */
    fun import(text: String, current: AppSettings): ImportResult {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ImportResult.Invalid("Keine gültige Sicherungsdatei")
        if ((root["format"] as? JsonPrimitive)?.content != FORMAT) return ImportResult.Invalid("Keine Sofa-Hub-Sicherung")
        val saved = root["settings"] as? JsonObject ?: return ImportResult.Invalid("Sicherung ohne Einstellungen")
        val restored = SettingsCodec.decode(JsonObject(saved - DEVICE_ONLY).toString())
        return ImportResult.Ok(
            restored.copy(
                setupCompleted = restored.setupCompleted || current.setupCompleted,
                photos = current.photos,
                background = current.background,
                pendingUpdate = current.pendingUpdate,
                lastUpdateCheckEpochMs = current.lastUpdateCheckEpochMs,
            ),
        )
    }
}
