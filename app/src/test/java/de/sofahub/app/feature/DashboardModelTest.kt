package de.sofahub.app.feature

import de.sofahub.app.core.model.ActionStatus
import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.HaArea
import de.sofahub.app.core.model.HaConnection
import de.sofahub.app.core.model.HaError
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.core.model.Role
import de.sofahub.app.feature.dashboard.Bindings
import de.sofahub.app.feature.dashboard.DashboardModel
import de.sofahub.app.feature.dashboard.RoleTile
import de.sofahub.app.feature.setup.applySelection
import de.sofahub.app.integration.homeassistant.entity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardModelTest {
    private val window = entity("binary_sensor.bad", "on", attrs = buildJsonObject { put("device_class", "window"); put("friendly_name", "Fenster Bad") })
    private val snap = HaSnapshot(
        connection = HaConnection.Connected,
        entities = listOf(
            entity("light.wz", "on"), entity("switch.tv", "unavailable"), window,
            entity("scene.film", "2026-10-30"), entity("weather.home", "cloudy", attrs = buildJsonObject { put("temperature", 11.4) }),
        ).associateBy { it.entityId },
        areas = mapOf("bad" to HaArea("bad", "Bad"), "wohnen" to HaArea("wohnen", "Wohnzimmer")),
        entityAreas = mapOf("binary_sensor.bad" to "bad", "light.wz" to "wohnen"),
        stale = false,
    )
    private val settings = AppSettings(
        roles = mapOf(Role.LIGHT to "light.wz", Role.TV_PLUG to "switch.tv", Role.MOVIE_SCENE to "scene.weg", Role.WEATHER to "weather.home"),
        windowEntities = listOf("binary_sensor.bad"),
        smartHomeEntities = listOf("light.wz", "switch.tv"),
    )
    private val b = Bindings.of(settings, demo = false)

    @Test fun tilesReflectRealState() {
        assertTrue(DashboardModel.entityTile("light.wz", snap) is RoleTile.Ready)
        assertEquals(RoleTile.Missing("scene.weg"), DashboardModel.entityTile("scene.weg", snap))
        val offline = snap.copy(connection = HaConnection.Retrying(HaError.Network, 0))
        assertTrue("HA-Ausfall pausiert, erfindet nichts", DashboardModel.entityTile("light.wz", offline) is RoleTile.Paused)
        val pending = snap.copy(actions = mapOf("light.wz" to ActionStatus.Pending))
        assertEquals(ActionStatus.Pending, (DashboardModel.entityTile("light.wz", pending) as RoleTile.Ready).action)
    }

    @Test fun houseSummaryCountsRoomsWindowsAndUnavailable() {
        val h = DashboardModel.house(b, snap)
        assertEquals(listOf("Wohnzimmer", "Bad"), h.roomNames)
        assertEquals(listOf("Bad"), h.openWindows)
        assertEquals(1, h.unavailableCount)
        assertTrue(h.available)
        assertEquals("11°" to "Bedeckt", DashboardModel.weather(b, snap))
        assertFalse(DashboardModel.house(b, snap.copy(connection = HaConnection.Disconnected)).available)
    }

    @Test fun manualRoomOverridesRegistry() {
        val bm = b.copy(manualRooms = mapOf("light.wz" to "Lounge"))
        assertEquals("Lounge", DashboardModel.roomOf("light.wz", bm, snap))
    }

    @Test fun setupSelectionFillsOnlyFreeRoles() {
        val s = AppSettings(roles = mapOf(Role.LIGHT to "light.vorhanden"))
        val out = applySelection(s, setOf("light.wz", "switch.tv", "binary_sensor.bad", "scene.film"), snap)
        assertEquals("light.vorhanden", out.roles[Role.LIGHT])
        assertEquals("switch.tv", out.roles[Role.TV_PLUG])
        assertEquals("scene.film", out.roles[Role.MOVIE_SCENE])
        assertEquals("weather.home", out.roles[Role.WEATHER])
        assertEquals(listOf("binary_sensor.bad"), out.windowEntities)
        assertTrue("scene.film" !in out.smartHomeEntities)
    }
}
