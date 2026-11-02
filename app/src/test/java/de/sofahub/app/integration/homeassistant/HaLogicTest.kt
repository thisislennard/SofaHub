package de.sofahub.app.integration.homeassistant

import de.sofahub.app.core.model.HaEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

fun entity(id: String, state: String, updated: String = "2026-10-31T19:00:00+00:00", attrs: JsonObject = JsonObject(emptyMap())) =
    HaEntity(id, state, attrs, updated, updated)

class HaUrlTest {
    private fun valid(input: String) = HaUrl.parse(input) as HaUrlResult.Valid

    @Test fun normalizesCommonInputs() {
        assertEquals("http://homeassistant.local:8123", valid("http://homeassistant.local:8123/").normalized)
        assertEquals("https://ha.example.de", valid("ha.example.de").normalized)
        assertEquals("http://192.168.178.20:8123", valid("192.168.178.20:8123").normalized)
        assertEquals("http://192.168.1.5", valid("192.168.1.5").normalized)
        assertEquals("http://192.168.1.5:8123", valid("  http://192.168.1.5:8123/api/states ").normalized)
        assertEquals("https://example.de/ha", valid("https://example.de/ha/").normalized)
        assertTrue(valid("http://ha.local:8123").isHttp)
        assertFalse(valid("https://ha.local").isHttp)
    }

    @Test fun rejectsDangerousOrBrokenInputs() {
        listOf("", "ftp://ha.local", "http://user:pw@ha.local", "http://ha.local/?x=1", "http://ha.local/#a", "http://ha local", "javascript://x")
            .forEach { assertTrue(it, HaUrl.parse(it) is HaUrlResult.Invalid) }
    }

    @Test fun websocketUrlFollowsScheme() {
        assertEquals("ws://ha.local:8123/api/websocket", HaUrl.websocket(valid("http://ha.local:8123").url))
        assertEquals("wss://ha.example.de/sub/api/websocket", HaUrl.websocket(valid("https://ha.example.de/sub").url))
    }
}

class HaServicesTest {
    @Test fun brightnessScalesAndClamps() {
        assertEquals(255, HaServices.percentToBrightness(100))
        assertEquals(128, HaServices.percentToBrightness(50))
        assertEquals(1, HaServices.percentToBrightness(0))
        assertEquals(255, HaServices.percentToBrightness(140))
        assertEquals(80, HaServices.brightnessToPercent(204))
        assertEquals(100, HaServices.brightnessToPercent(400))
    }

    @Test fun zeroPercentTurnsOffInsteadOfDimming() {
        val call = HaServices.setBrightness("light.a", 0)
        assertEquals("turn_off", call.service)
        val dim = HaServices.setBrightness("light.a", 30)
        assertEquals("turn_on", dim.service)
        assertEquals(77, dim.data["brightness"]!!.jsonPrimitive.int)
    }

    @Test fun explicitOnOffNoToggle() {
        assertEquals("turn_on", HaServices.setOn("switch.tv", true).service)
        assertEquals("turn_off", HaServices.setOn("switch.tv", false).service)
        assertEquals("switch", HaServices.setOn("switch.tv", true).domain)
    }

    @Test fun scenesAndScriptsAreNotIdempotent() {
        assertFalse(HaServices.activateScene("scene.film").idempotent)
        assertFalse(HaServices.runScript("script.x").idempotent)
        assertTrue(HaServices.turnOn("light.a").idempotent)
    }

    @Test fun capabilitiesOnlyFromAttributes() {
        val onOff = entity("light.a", "on", attrs = buildJsonObject { put("supported_color_modes", buildJsonArray { add(JsonPrimitive("onoff")) }) })
        val dimmable = entity("light.b", "on", attrs = buildJsonObject { put("brightness", 204); put("supported_color_modes", buildJsonArray { add(JsonPrimitive("brightness")) }) })
        assertFalse(EntityView.isDimmable(onOff))
        assertTrue(EntityView.isDimmable(dimmable))
        assertEquals("An · 80 %", EntityView.stateLabel(dimmable))
        // Fenster nur mit passender device_class
        assertFalse(EntityView.isWindowSensor(entity("binary_sensor.fenster_bad", "on")))
        assertTrue(EntityView.isWindowSensor(entity("binary_sensor.x", "on", attrs = buildJsonObject { put("device_class", "window") })))
        // Temperatur nicht aus dem Namen raten
        assertFalse(EntityView.isTemperature(entity("sensor.temperatur_wohnzimmer", "21")))
        val tv = entity("media_player.tv", "on", attrs = buildJsonObject { put("supported_features", 128 + 256 + 1024) })
        assertEquals(setOf(MediaAction.POWER_ON, MediaAction.POWER_OFF, MediaAction.VOLUME_UP, MediaAction.VOLUME_DOWN), EntityView.mediaSupports(tv))
        assertEquals(emptySet<MediaAction>(), EntityView.mediaSupports(entity("media_player.y", "on")))
    }

    @Test fun unavailableAndUnknownAreDistinct() {
        assertEquals("Nicht erreichbar", EntityView.stateLabel(entity("light.a", "unavailable")))
        assertEquals("Unbekannt", EntityView.stateLabel(entity("light.a", "unknown")))
        assertNull(EntityView.temperatureText(entity("sensor.t", "unavailable")))
    }

    @Test fun backoffIsBoundedAndGrows() {
        val r = Random(1)
        val values = (0..12).map { RealHomeAssistantRepository.backoffMs(it, r) }
        assertTrue(values.first() in 800..1200)
        assertTrue(values.all { it <= 72_000 })
        assertTrue(values.last() >= 48_000)
    }
}

class StateMergerTest {
    private val t1 = "2026-10-31T19:00:00+00:00"
    private val t2 = "2026-10-31T19:00:05+00:00"
    private val t3 = "2026-10-31T19:00:09+00:00"

    @Test fun eventsBeforeSnapshotAreBufferedAndNewerOnesWin() {
        val m = StateMerger()
        // Änderung passiert, während der Snapshot läuft: neuer als der Snapshot-Stand -> gewinnt.
        assertFalse(m.onEvent(StateMerger.Change("light.a", entity("light.a", "on", t3), t1)))
        // Älteres Event als der Snapshot -> wird verworfen.
        m.onEvent(StateMerger.Change("switch.b", entity("switch.b", "off", t1), null))
        m.onSnapshot(listOf(entity("light.a", "off", t2), entity("switch.b", "on", t2)))
        assertEquals("on", m.entities()["light.a"]!!.state)
        assertEquals("on", m.entities()["switch.b"]!!.state)
    }

    @Test fun eventsAfterSnapshotApplyImmediately() {
        val m = StateMerger()
        m.onSnapshot(listOf(entity("light.a", "off", t1)))
        assertTrue(m.onEvent(StateMerger.Change("light.a", entity("light.a", "on", t2), t1)))
        assertEquals("on", m.entities()["light.a"]!!.state)
        // Neue Entität erscheint
        assertTrue(m.onEvent(StateMerger.Change("light.neu", entity("light.neu", "on", t2), null)))
        assertTrue("light.neu" in m.entities())
    }

    @Test fun deletionIsTrackedAndRecreationClearsIt() {
        val m = StateMerger()
        m.onSnapshot(listOf(entity("light.a", "on", t1)))
        assertTrue(m.onEvent(StateMerger.Change("light.a", null, t1)))
        assertFalse("light.a" in m.entities())
        assertTrue("light.a" in m.deleted())
        m.onEvent(StateMerger.Change("light.a", entity("light.a", "off", t3), null))
        assertFalse("light.a" in m.deleted())
    }

    @Test fun staleDeletionBeforeSnapshotDoesNotRemoveRecreatedEntity() {
        val m = StateMerger()
        m.onEvent(StateMerger.Change("light.a", null, t1))
        m.onSnapshot(listOf(entity("light.a", "on", t2)))
        assertTrue("light.a" in m.entities())
    }

    @Test fun reapplyingSameEventIsNoChange() {
        val m = StateMerger()
        val e = entity("light.a", "on", t2)
        m.onSnapshot(listOf(e))
        assertFalse(m.onEvent(StateMerger.Change("light.a", e, t1)))
    }
}
