package de.sofahub.app.integration.homeassistant

import de.sofahub.app.core.model.HaEntity
import java.time.OffsetDateTime

/**
 * Führt Snapshot (get_states) und state_changed-Events lückenlos zusammen.
 *
 * Die Subscription läuft vor dem Snapshot. Events, die vor dem Snapshot eintreffen, werden gepuffert und danach nur
 * angewendet, wenn sie neuer sind als der Snapshot-Stand der Entität. So geht weder eine Änderung zwischen Snapshot und
 * Subscription verloren, noch überschreibt ein älteres Event einen neueren Snapshot-Wert.
 */
class StateMerger {
    private val buffer = mutableListOf<Change>()
    private var snapshotApplied = false
    private val entities = LinkedHashMap<String, HaEntity>()
    private val deleted = mutableSetOf<String>()

    data class Change(val entityId: String, val newState: HaEntity?, val oldLastUpdated: String?)

    val hasSnapshot: Boolean get() = snapshotApplied

    /** @return true, wenn sich der sichtbare Zustand geändert hat. */
    fun onEvent(change: Change): Boolean {
        if (!snapshotApplied) {
            buffer += change
            return false
        }
        return apply(change)
    }

    fun onSnapshot(states: List<HaEntity>) {
        entities.clear()
        deleted.clear()
        states.forEach { entities[it.entityId] = it }
        snapshotApplied = true
        buffer.forEach { apply(it) }
        buffer.clear()
    }

    fun entities(): Map<String, HaEntity> = LinkedHashMap(entities)
    fun deleted(): Set<String> = deleted.toSet()

    private fun apply(change: Change): Boolean {
        val current = entities[change.entityId]
        val new = change.newState
        if (new == null) {
            // Löschung nur, wenn der bekannte Stand nicht neuer ist als der gelöschte (sonst wurde neu angelegt).
            if (current != null && change.oldLastUpdated != null && isNewer(current.lastUpdated, change.oldLastUpdated)) return false
            val removed = entities.remove(change.entityId) != null
            deleted += change.entityId
            return removed
        }
        if (current != null && isNewer(current.lastUpdated, new.lastUpdated)) return false
        entities[change.entityId] = new
        deleted -= change.entityId
        return current != new
    }

    companion object {
        /** true, wenn [a] strikt neuer ist als [b]. Unlesbare Zeitstempel gelten als nicht neuer (Event gewinnt). */
        fun isNewer(a: String, b: String): Boolean {
            val ta = parse(a) ?: return false
            val tb = parse(b) ?: return false
            return ta.isAfter(tb)
        }

        private fun parse(s: String): OffsetDateTime? = runCatching { OffsetDateTime.parse(s) }.getOrNull()
    }
}
