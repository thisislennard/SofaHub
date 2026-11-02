package de.sofahub.app.core.storage

import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Liest und schreibt [AppSettings] als JSON, migriert ältere Schemas und rettet bei beschädigten Einzelwerten alle
 * übrigen Einstellungen (ein kaputtes Feld fällt auf seinen Default zurück, statt die ganze Konfiguration zu löschen).
 */
object SettingsCodec {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Migrationen: Schlüssel = Ausgangsversion. Jede Funktion hebt das Objekt um genau eine Version an. */
    private val migrations: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        // Schema 0 = Vorversion ohne schemaVersion-Feld (nur Entwicklungsstände): Feldnamen sind identisch.
        0 to { obj -> JsonObject(obj + ("schemaVersion" to JsonPrimitive(1))) },
    )

    fun encode(settings: AppSettings): String = json.encodeToString(AppSettings.serializer(), settings)

    fun decode(text: String): AppSettings {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return AppSettings()
        val migrated = sanitize(migrate(root))
        return runCatching { json.decodeFromJsonElement(AppSettings.serializer(), migrated) }
            .getOrElse { decodeTolerant(migrated) }
    }

    fun migrate(input: JsonObject): JsonObject {
        var obj = input
        var version = (obj["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: 0
        // Neueres Schema (z. B. nach Downgrade): unbekannte Felder werden ignoriert, Bekanntes bleibt erhalten.
        while (version < AppSettings.CURRENT_SCHEMA) {
            val step = migrations[version] ?: error("Keine Migration von Schema $version")
            obj = step(obj)
            version++
        }
        return obj
    }

    /**
     * Unbekannte Rollen (z. B. nach einem Downgrade) würden sonst die gesamte Rollen-Map unlesbar machen:
     * nur bekannte Rollen behalten.
     */
    private fun sanitize(obj: JsonObject): JsonObject {
        val roles = obj["roles"] as? JsonObject ?: return obj
        val known = Role.entries.map { it.name }.toSet()
        return JsonObject(obj + ("roles" to JsonObject(roles.filterKeys { it in known })))
    }

    /** Übernimmt Feld für Feld nur das, was sich fehlerfrei lesen lässt. */
    private fun decodeTolerant(obj: JsonObject): AppSettings {
        var accepted = buildJsonObject { }
        for ((key, value) in obj) {
            val candidate = JsonObject(accepted + (key to value))
            if (runCatching { json.decodeFromJsonElement(AppSettings.serializer(), candidate) }.isSuccess) {
                accepted = candidate
            }
        }
        return runCatching { json.decodeFromJsonElement(AppSettings.serializer(), accepted) }.getOrDefault(AppSettings())
    }
}
