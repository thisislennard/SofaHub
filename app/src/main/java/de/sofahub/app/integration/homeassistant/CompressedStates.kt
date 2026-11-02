package de.sofahub.app.integration.homeassistant

import de.sofahub.app.core.model.HaEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant

/**
 * Wendet Nachrichten von `subscribe_entities` auf eine Entitätenliste an. HA schickt dabei ein kompaktes Format:
 * `a` = vollständig hinzugefügt (`s` Zustand, `a` Attribute, `lc`/`lu` Zeitstempel in Sekunden), `c` = Änderungen
 * (`+` neue Werte, `-` entfernte Attribute), `r` = entfernt. Damit lauscht Sofa Hub im Hintergrund nur auf wenige
 * Entitäten statt auf jede Zustandsänderung im Haus.
 */
object CompressedStates {
    fun apply(current: Map<String, HaEntity>, event: JsonObject): Map<String, HaEntity> {
        val out = current.toMutableMap()
        (event["a"] as? JsonObject)?.forEach { (id, value) ->
            val o = value as? JsonObject ?: return@forEach
            if (!id.contains('.')) return@forEach
            val changed = o.time("lc").orEmpty()
            out[id] = HaEntity(
                entityId = id,
                state = o.str("s") ?: "unknown",
                attributes = o["a"] as? JsonObject ?: JsonObject(emptyMap()),
                lastChanged = changed,
                lastUpdated = o.time("lu") ?: changed,
            )
        }
        (event["c"] as? JsonObject)?.forEach { (id, value) ->
            val old = out[id] ?: return@forEach
            val diff = value as? JsonObject ?: return@forEach
            val plus = diff["+"] as? JsonObject
            val removed = (diff["-"] as? JsonObject)?.get("a") as? JsonArray
            val attrs = old.attributes.toMutableMap()
            (plus?.get("a") as? JsonObject)?.let { attrs.putAll(it) }
            removed?.forEach { k -> (k as? JsonPrimitive)?.content?.let { attrs.remove(it) } }
            val changed = plus?.time("lc")
            out[id] = old.copy(
                state = plus?.str("s") ?: old.state,
                attributes = JsonObject(attrs),
                lastChanged = changed ?: old.lastChanged,
                lastUpdated = plus?.time("lu") ?: changed ?: old.lastUpdated,
            )
        }
        (event["r"] as? JsonArray)?.forEach { (it as? JsonPrimitive)?.content?.let(out::remove) }
        return out
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    /** Sekunden seit 1970 (mit Nachkommastellen) als ISO-Zeit wie bei get_states. */
    private fun JsonObject.time(key: String): String? =
        (this[key] as? JsonPrimitive)?.doubleOrNull?.let { Instant.ofEpochMilli((it * 1000).toLong()).toString() }
}
