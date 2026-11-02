package de.sofahub.app.feature.dashboard

import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.ui.components.eveningWarmth
import de.sofahub.app.ui.components.greeting
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticesTest {
    private fun e(id: String, state: String, attrs: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()) =
        HaEntity(id, state, JsonObject(attrs + ("friendly_name" to JsonPrimitive(id.substringAfter('.').replaceFirstChar { it.uppercase() }))), "", "")

    private fun printer(status: String, progress: Int) = listOf(
        e("camera.p2s_kamera", "idle"),
        e("sensor.p2s_druckstatus", status),
        e("sensor.p2s_druckfortschritt", "$progress"),
        e("sensor.p2s_name_des_druckers", "P2S"),
    )

    private fun snap(list: List<HaEntity>) = HaSnapshot(entities = list.associateBy { it.entityId })

    @Test fun `printer start, milestones and finish are announced once`() {
        val t = NoticeTracker(minGapMs = 0)
        assertTrue("Erster Blick meldet nichts", t.update(snap(printer("idle", 0)), emptyList(), 0).isEmpty())
        assertEquals("P2S: Druck gestartet", t.update(snap(printer("running", 3)), emptyList(), 1).single().text)
        assertTrue(t.update(snap(printer("running", 20)), emptyList(), 2).isEmpty())
        assertEquals("P2S: 50 %", t.update(snap(printer("running", 52)), emptyList(), 3).single().text)
        assertTrue(t.update(snap(printer("running", 60)), emptyList(), 4).isEmpty())
        assertEquals("P2S: Druck fertig", t.update(snap(printer("finish", 100)), emptyList(), 5).single().text)
        assertTrue(t.update(snap(printer("idle", 0)), emptyList(), 6).isEmpty())
    }

    @Test fun `pause is a warning and failure is reported`() {
        val t = NoticeTracker(minGapMs = 0)
        t.update(snap(printer("running", 10)), emptyList(), 0)
        assertTrue(t.update(snap(printer("pause", 10)), emptyList(), 1).single().warn)
        assertEquals("P2S: Druck fehlgeschlagen", t.update(snap(printer("failed", 10)), emptyList(), 2).single().text)
    }

    @Test fun `watched entity changes are announced and rate limited`() {
        val t = NoticeTracker(minGapMs = 60_000)
        val id = "binary_sensor.waschmaschine"
        t.update(snap(listOf(e(id, "on"))), listOf(id), 0)
        assertEquals("Waschmaschine: Aus", t.update(snap(listOf(e(id, "off"))), listOf(id), 1_000).single().text)
        assertTrue("innerhalb einer Minute still", t.update(snap(listOf(e(id, "on"))), listOf(id), 2_000).isEmpty())
        assertTrue(t.update(snap(listOf(e(id, "off"))), listOf(id), 70_000).isNotEmpty())
    }

    @Test fun `changes that happened long ago are taken over silently`() {
        val t = NoticeTracker(minGapMs = 0)
        val id = "sensor.waschmaschine"
        val now = java.time.Instant.parse("2026-11-01T20:00:00Z").toEpochMilli()
        fun at(state: String, changed: String) = snap(listOf(e(id, state).copy(lastChanged = changed)))
        t.update(at("Läuft", "2026-11-01T17:00:00+00:00"), listOf(id), now)
        assertTrue("Fertig seit Stunden (Fernseher war aus)", t.update(at("Fertig", "2026-11-01T18:30:00.123456+00:00"), listOf(id), now).isEmpty())
        assertEquals("Waschmaschine: Läuft", t.update(at("Läuft", "2026-11-01T19:59:58Z"), listOf(id), now).single().text)
        val p = printer("running", 10)
        t.update(snap(p), emptyList(), now)
        val finishedLongAgo = printer("finish", 100).map { if (it.entityId.endsWith("druckstatus")) it.copy(lastChanged = "2026-11-01T12:00:00+00:00") else it }
        assertTrue(t.update(snap(finishedLongAgo), emptyList(), now).isEmpty())
    }

    @Test fun `light glow uses rgb color and brightness`() {
        val on = e("light.wz", "on", mapOf("rgb_color" to JsonArray(listOf(255, 0, 0).map { JsonPrimitive(it) }), "brightness" to JsonPrimitive(128)))
        val g = LightLook.glow(on)!!
        assertEquals(1f, g.color.red, .01f)
        assertEquals(.5f, g.brightness, .01f)
        assertNull(LightLook.glow(e("light.wz", "off")))
        assertNull(LightLook.glow(e("switch.tv", "on")))
    }

    @Test fun `greeting and evening warmth follow the clock`() {
        assertEquals("Guten Morgen", greeting(7))
        assertEquals("Guten Abend", greeting(20))
        assertEquals("Gute Nacht", greeting(2))
        assertEquals(0f, eveningWarmth(14, 0), 0f)
        assertEquals(.5f, eveningWarmth(20, 0), .01f)
        assertEquals(1f, eveningWarmth(23, 0), 0f)
        assertEquals(.5f, eveningWarmth(3, 0), .01f)
    }
}
