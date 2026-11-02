package de.sofahub.app.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull

/** Zustand einer HA-Entität, wie ihn REST/WebSocket liefern. Attribute bleiben roh, Auswertung in [EntityView]. */
data class HaEntity(
    val entityId: String,
    val state: String,
    val attributes: JsonObject,
    val lastChanged: String,
    val lastUpdated: String,
) {
    /** Einmal berechnet statt bei jedem Zugriff (Filter laufen über alle Entitäten des Hauses). */
    val domain: String = entityId.substringBefore('.')
    val availability: Availability
        get() = when (state) {
            "unavailable" -> Availability.UNAVAILABLE
            "unknown" -> Availability.UNKNOWN
            else -> Availability.NORMAL
        }
    val friendlyName: String get() = attrString("friendly_name") ?: entityId

    fun attrString(key: String): String? = (attributes[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun attrDouble(key: String): Double? = (attributes[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
    fun attrLong(key: String): Long? = (attributes[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    fun attrStringList(key: String): List<String> =
        runCatching { attributes[key]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content } }.getOrNull() ?: emptyList()
    fun attr(key: String): JsonElement? = attributes[key]
}

enum class Availability { NORMAL, UNKNOWN, UNAVAILABLE }

sealed interface HaConnection {
    /** Keine HA-Konfiguration (übersprungen oder getrennt). */
    data object NotConfigured : HaConnection
    data object Disconnected : HaConnection
    data object Connecting : HaConnection
    data object Connected : HaConnection
    /** Token ungültig/abgelaufen: keine weiteren Versuche, Nutzer muss neu einrichten. */
    data object AuthFailed : HaConnection
    /** Gespeicherter Token ließ sich nicht entschlüsseln (z. B. Keystore zurückgesetzt). */
    data object TokenUnreadable : HaConnection
    /** Verbindung verloren, nächster Versuch geplant. */
    data class Retrying(val error: HaError, val nextAttemptEpochMs: Long) : HaConnection
}

data class HaArea(val id: String, val name: String)

/** Ergebnis eines Serviceaufrufs aus Sicht der Oberfläche. Quittiert heißt nicht: Gerät hat den Zustand schon erreicht. */
sealed interface ActionStatus {
    data object Pending : ActionStatus
    data object Acknowledged : ActionStatus
    data class Failed(val error: HaError) : ActionStatus
}

data class HaSnapshot(
    val connection: HaConnection = HaConnection.NotConfigured,
    val entities: Map<String, HaEntity> = emptyMap(),
    /** Entitäten, die während der Sitzung aus HA verschwunden sind (gelöscht/umbenannt). */
    val deleted: Set<String> = emptySet(),
    val areas: Map<String, HaArea> = emptyMap(),
    /** entity_id -> area_id aus Entity-/Device-Registry, sofern lesbar. */
    val entityAreas: Map<String, String> = emptyMap(),
    val registryAvailable: Boolean = false,
    val lastSyncEpochMs: Long = 0,
    /** Werte stammen aus einer früheren Verbindung und können veraltet sein. */
    val stale: Boolean = true,
    val actions: Map<String, ActionStatus> = emptyMap(),
)
