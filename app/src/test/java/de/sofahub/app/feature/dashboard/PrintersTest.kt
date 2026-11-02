package de.sofahub.app.feature.dashboard

import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.HaSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintersTest {
    private fun e(id: String, state: String, vararg attrs: Pair<String, String>) =
        HaEntity(id, state, JsonObject(attrs.associate { it.first to JsonPrimitive(it.second) }), "", "")

    private fun snap(vararg list: HaEntity) = HaSnapshot(entities = list.associateBy { it.entityId })

    private val p2s = "p2s_22e8bj620500482"

    private fun printing(status: String = "running") = snap(
        e("camera.${p2s}_kamera", "idle", "friendly_name" to "P2S_22E8BJ620500482 Kamera"),
        e("sensor.${p2s}_druckstatus", status),
        e("sensor.${p2s}_druckfortschritt", "47"),
        e("sensor.${p2s}_verbleibende_zeit", "72", "unit_of_measurement" to "min"),
        e("sensor.${p2s}_aktuelle_schicht", "120"),
        e("sensor.${p2s}_gesamtzahl_der_schichten", "310"),
        e("sensor.${p2s}_name_der_aufgabe", "Halterung"),
        e("sensor.${p2s}_name_des_druckers", "P2S"),
    )

    @Test fun `running bambu printer is detected with details`() {
        val job = Printers.active(printing()).single()
        assertEquals("camera.${p2s}_kamera", job.cameraId)
        assertEquals("P2S", job.printer)
        assertEquals(47, job.progress)
        assertEquals(72, job.remainingMinutes)
        assertEquals("Schicht 120/310", job.layer)
        assertEquals("Halterung", job.task)
        assertEquals("noch 1:12 h", Printers.remainingLabel(job.remainingMinutes!!))
    }

    @Test fun `idle finished or offline printers are hidden`() {
        listOf("idle", "finish", "failed", "offline", "unavailable").forEach { assertTrue(it, Printers.active(printing(it)).isEmpty()) }
    }

    @Test fun `pause and prepare count as active`() {
        assertTrue(Printers.active(printing("pause")).single().paused)
        assertEquals("Bereitet vor", Printers.statusLabel(Printers.active(printing("prepare")).single()))
    }

    @Test fun `remaining time respects hours unit and name falls back to camera`() {
        val s = snap(
            e("camera.a1_camera", "idle", "friendly_name" to "A1 Camera"),
            e("sensor.a1_print_status", "running"),
            e("sensor.a1_remaining_time", "1.5", "unit_of_measurement" to "h"),
        )
        val job = Printers.active(s).single()
        assertEquals(90, job.remainingMinutes)
        assertEquals("A1", job.printer)
        assertNull(job.progress)
    }

    @Test fun `cameras without printer sensors are ignored`() {
        assertTrue(Printers.active(snap(e("camera.haustuer", "idle"), e("camera.garten_kamera", "idle"))).isEmpty())
    }

    @Test fun `watch ids cover only the notice sensors of detected printers`() {
        val s = printing().let { it.copy(entities = it.entities + listOf(e("sensor.other", "1"), e("camera.door", "idle")).associateBy { e -> e.entityId }) }
        val ids = Printers.watchIds(s)
        assertEquals(
            setOf("druckstatus", "druckfortschritt", "verbleibende_zeit", "name_der_aufgabe").map { "sensor.${p2s}_$it" }.toSet(),
            ids,
        )
    }
}
