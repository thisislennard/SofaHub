package de.sofahub.app.integration.homeassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressedStatesTest {
    private fun ev(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun `add, change and remove in the compact subscribe_entities format`() {
        var m = CompressedStates.apply(emptyMap(), ev("""{"a":{"sensor.wm":{"s":"Läuft","a":{"friendly_name":"Waschmaschine","icon":"mdi:x"},"c":"ctx","lc":1767268800.5}}}"""))
        val wm = m.getValue("sensor.wm")
        assertEquals("Läuft", wm.state)
        assertEquals("Waschmaschine", wm.friendlyName)
        assertEquals("2026-01-01T12:00:00.500Z", wm.lastChanged)
        assertEquals(wm.lastChanged, wm.lastUpdated)

        m = CompressedStates.apply(m, ev("""{"c":{"sensor.wm":{"+":{"s":"Fertig","a":{"rest":0},"lc":1767272400.0},"-":{"a":["icon"]}}}}"""))
        val done = m.getValue("sensor.wm")
        assertEquals("Fertig", done.state)
        assertEquals("Waschmaschine", done.friendlyName)
        assertEquals(JsonPrimitive(0), done.attributes["rest"])
        assertNull(done.attributes["icon"])

        // Nur Attribute geändert: Zustand bleibt.
        m = CompressedStates.apply(m, ev("""{"c":{"sensor.wm":{"+":{"a":{"rest":5},"lu":1767272460.0}}}}"""))
        assertEquals("Fertig", m.getValue("sensor.wm").state)
        assertEquals("2026-01-01T13:01:00Z", m.getValue("sensor.wm").lastUpdated)

        m = CompressedStates.apply(m, ev("""{"r":["sensor.wm"]}"""))
        assertTrue(m.isEmpty())
    }

    @Test fun `changes for unknown entities and malformed ids are ignored`() {
        val m = CompressedStates.apply(emptyMap(), ev("""{"c":{"sensor.x":{"+":{"s":"1"}}},"a":{"kaputt":{"s":"1"}}}"""))
        assertTrue(m.isEmpty())
    }
}
