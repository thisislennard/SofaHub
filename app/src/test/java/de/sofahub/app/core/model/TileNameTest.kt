package de.sofahub.app.core.model

import de.sofahub.app.core.storage.SettingsCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class TileNameTest {
    @Test fun `custom name wins, blank falls back to default`() {
        val s = AppSettings(roleNames = mapOf(Role.TV_PLUG to "  Ladestation Auto ", Role.LIGHT to " "))
        assertEquals("Ladestation Auto", s.tileName(Role.TV_PLUG))
        assertEquals("Wohnzimmerlicht", s.tileName(Role.LIGHT))
        assertEquals("Filmabend", s.tileName(Role.MOVIE_SCENE))
    }

    @Test fun `names survive storage round trip`() {
        val s = AppSettings(roleNames = mapOf(Role.TV_PLUG to "Ladestation Auto"))
        assertEquals("Ladestation Auto", SettingsCodec.decode(SettingsCodec.encode(s)).tileName(Role.TV_PLUG))
    }
}
