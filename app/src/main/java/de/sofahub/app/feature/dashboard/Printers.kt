package de.sofahub.app.feature.dashboard

import de.sofahub.app.core.model.Availability
import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.HaSnapshot
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/** Ein 3D-Drucker, der gerade druckt, mit seiner Kamera (Bambu-Lab-Integration, deutsch oder englisch benannt). */
data class PrintJob(
    val cameraId: String,
    val printer: String,
    val status: String,
    val progress: Int?,
    val remainingMinutes: Int?,
    val layer: String?,
    val task: String?,
    /** Wann der Druckstatus zuletzt wechselte (ISO-Zeit aus HA, leer = unbekannt). */
    val statusChanged: String = "",
) {
    val paused get() = status == "pause"
    val active get() = status in setOf("running", "prepare", "pause", "slicing")
}

object Printers {
    private val CAMERA_SUFFIXES = listOf("_kamera", "_camera")
    private val ACTIVE = setOf("running", "prepare", "pause", "slicing")

    /** Alle Drucker, die gerade drucken, vorbereiten oder pausiert sind – sortiert nach Name. */
    fun active(snap: HaSnapshot): List<PrintJob> = snap.entities.values
        .filter { it.domain == "camera" }
        .mapNotNull { cam -> jobFor(cam, snap.entities) }
        .sortedBy { it.printer.lowercase() }

    /** Alle erkannten Drucker mit ihrem Status, auch fertige oder ruhende (für Meldungen). */
    fun all(snap: HaSnapshot): List<PrintJob> = snap.entities.values
        .filter { it.domain == "camera" }
        .mapNotNull { cam -> jobFor(cam, snap.entities, activeOnly = false) }

    /**
     * Sensoren aller erkannten Drucker, die ihre Meldungen auslösen – im Hintergrund nur diese. Temperaturen, Schichten
     * und die Kamera (deren Bild-Token sich laufend ändert) bleiben weg, damit während eines Films kaum Daten fließen.
     */
    fun watchIds(snap: HaSnapshot): Set<String> = snap.entities.values
        .filter { it.domain == "camera" && jobFor(it, snap.entities, activeOnly = false) != null }
        .flatMapTo(mutableSetOf()) { cam ->
            val base = sensorBase(cam) ?: return@flatMapTo emptyList()
            NOTICE_KEYS.map { "${base}_$it" }.filter { it in snap.entities }
        }

    private val NOTICE_KEYS = listOf(
        "druckstatus", "print_status", "druckfortschritt", "print_progress", "verbleibende_zeit", "remaining_time",
        "name_der_aufgabe", "task_name",
    )

    private fun sensorBase(camera: HaEntity): String? {
        val name = camera.entityId.substringAfter('.')
        val suffix = CAMERA_SUFFIXES.firstOrNull { name.endsWith(it) } ?: return null
        return "sensor." + name.removeSuffix(suffix)
    }

    fun jobFor(camera: HaEntity, entities: Map<String, HaEntity>, activeOnly: Boolean = true): PrintJob? {
        val base = sensorBase(camera) ?: return null
        fun sensor(vararg keys: String) = keys.firstNotNullOfOrNull { k ->
            entities["${base}_$k"]?.takeIf { it.availability == Availability.NORMAL }
        }
        val statusEntity = sensor("druckstatus", "print_status") ?: return null
        val status = statusEntity.state.lowercase()
        if (activeOnly && status !in ACTIVE) return null
        val current = sensor("aktuelle_schicht", "current_layer")?.state?.toIntOrNull()
        val total = sensor("gesamtzahl_der_schichten", "total_layer_count")?.state?.toIntOrNull()
        return PrintJob(
            cameraId = camera.entityId,
            printer = sensor("name_des_druckers", "printer_name")?.state
                ?: camera.friendlyName.removeSuffix(" Kamera").removeSuffix(" Camera"),
            status = status,
            progress = sensor("druckfortschritt", "print_progress")?.state?.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100),
            remainingMinutes = sensor("verbleibende_zeit", "remaining_time")?.let { minutes(it) },
            layer = if (current != null && total != null && total > 0) "Schicht $current/$total" else null,
            task = sensor("name_der_aufgabe", "task_name")?.state?.takeIf { it.isNotBlank() },
            statusChanged = statusEntity.lastChanged,
        )
    }

    private fun minutes(e: HaEntity): Int? {
        val v = e.state.toDoubleOrNull() ?: return null
        val unit = (e.attributes["unit_of_measurement"] as? JsonPrimitive)?.content
        return when (unit) {
            "h" -> v * 60
            "s" -> v / 60
            "d" -> v * 1440
            else -> v
        }.roundToInt().coerceAtLeast(0)
    }

    fun statusLabel(job: PrintJob) = when (job.status) {
        "pause" -> "Pausiert"
        "prepare", "slicing" -> "Bereitet vor"
        else -> "Druckt"
    }

    fun remainingLabel(minutes: Int): String = if (minutes >= 60) "noch ${minutes / 60}:${"%02d".format(minutes % 60)} h" else "noch $minutes min"
}
