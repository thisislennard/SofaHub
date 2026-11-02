package de.sofahub.app.core.model

import de.sofahub.app.core.storage.SettingsCodec
import de.sofahub.app.feature.dashboard.TileKind
import de.sofahub.app.feature.dashboard.tileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTilesTest {
    @Test fun `without own list the old fixed tiles are derived with their names`() {
        val s = AppSettings(
            roles = mapOf(Role.TV_PLUG to "switch.lader", Role.LIGHT to "light.wz", Role.WEATHER to "weather.home"),
            roleNames = mapOf(Role.TV_PLUG to "Ladestation Auto"),
        )
        assertEquals(listOf(HomeTile("light.wz", "Wohnzimmerlicht"), HomeTile("switch.lader", "Ladestation Auto")), s.effectiveHomeTiles())
    }

    @Test fun `own list wins, also when empty, but not in demo`() {
        val s = AppSettings(roles = mapOf(Role.LIGHT to "light.wz"), homeTiles = emptyList())
        assertTrue(s.effectiveHomeTiles().isEmpty())
        assertEquals(1, s.effectiveHomeTiles(mapOf(Role.LIGHT to "light.demo"), demo = true).size)
    }

    @Test fun `layout keeps all blocks once and survives storage`() {
        val l = HomeLayout(order = listOf(HomeBlock.HOUSE, HomeBlock.HOUSE))
        assertEquals(listOf(HomeBlock.HOUSE, HomeBlock.SCENES, HomeBlock.APPS), l.blocks)
        val s = AppSettings(homeLayout = l.copy(showMirror = false), homeTiles = listOf(HomeTile("sensor.x", "X")), sceneBar = listOf("scene.kino"))
        val back = SettingsCodec.decode(SettingsCodec.encode(s))
        assertEquals(s.homeLayout, back.homeLayout)
        assertEquals(s.homeTiles, back.homeTiles)
        assertEquals(s.sceneBar, back.sceneBar)
    }

    @Test fun `tile kind follows domain`() {
        assertEquals(TileKind.SWITCH, tileKind("light.a"))
        assertEquals(TileKind.SWITCH, tileKind("fan.a"))
        assertEquals(TileKind.SCENE, tileKind("script.a"))
        assertEquals(TileKind.VALUE, tileKind("sensor.a"))
        assertEquals(TileKind.VALUE, tileKind("media_player.a"))
    }
}
