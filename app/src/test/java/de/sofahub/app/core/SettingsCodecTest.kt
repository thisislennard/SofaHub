package de.sofahub.app.core

import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.FavoriteApp
import de.sofahub.app.core.model.HaConfig
import de.sofahub.app.core.model.Role
import de.sofahub.app.core.storage.SettingsCodec
import de.sofahub.app.core.storage.maskSecret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCodecTest {
    private val sample = AppSettings(
        setupCompleted = true,
        ha = HaConfig("http://homeassistant.local:8123", allowHttp = true),
        roles = mapOf(Role.LIGHT to "light.wohnzimmer", Role.MOVIE_SCENE to "scene.filmabend"),
        windowEntities = listOf("binary_sensor.bad"),
        favorites = listOf(FavoriteApp("com.netflix.ninja"), FavoriteApp("com.spotify.tv.android", "Musik")),
        note = "Müll rausbringen\nDienstag",
    )

    @Test fun roundTripKeepsEverything() {
        assertEquals(sample, SettingsCodec.decode(SettingsCodec.encode(sample)))
    }

    @Test fun neverContainsToken() {
        // Der Token gehört nicht in die Einstellungen; das Modell hat dafür nicht einmal ein Feld.
        val json = SettingsCodec.encode(sample)
        assertFalse(json.contains("token", ignoreCase = true))
    }

    @Test fun migratesSchemaZeroWithoutLosingValues() {
        val old = """{"setupCompleted":true,"note":"alt","favorites":[{"packageName":"a.b"}]}"""
        val s = SettingsCodec.decode(old)
        assertEquals(AppSettings.CURRENT_SCHEMA, s.schemaVersion)
        assertTrue(s.setupCompleted)
        assertEquals("alt", s.note)
        assertEquals(listOf(FavoriteApp("a.b")), s.favorites)
    }

    @Test fun brokenSingleFieldFallsBackToDefaultOnly() {
        val broken = """{"schemaVersion":1,"setupCompleted":true,"note":"bleibt","favorites":"kaputt","slideshowSeconds":12}"""
        val s = SettingsCodec.decode(broken)
        assertTrue(s.setupCompleted)
        assertEquals("bleibt", s.note)
        assertEquals(12, s.slideshowSeconds)
        assertEquals(emptyList<FavoriteApp>(), s.favorites)
    }

    @Test fun unknownFieldsAndRolesAreIgnored() {
        val future = """{"schemaVersion":1,"note":"x","neuesFeld":42,"roles":{"LIGHT":"light.a","UNBEKANNT":"x.y"}}"""
        val s = SettingsCodec.decode(future)
        assertEquals("x", s.note)
        assertEquals("light.a", s.roles[Role.LIGHT])
    }

    @Test fun garbageGivesDefaults() {
        assertEquals(AppSettings(), SettingsCodec.decode("nicht json"))
        assertEquals(AppSettings(), SettingsCodec.decode("[1,2]"))
    }

    @Test fun maskShowsOnlyLastFour() {
        assertEquals("••••••••••••••••a3F9", maskSecret("eyJhbGciOiJIUzI1NiJ9.abc.a3F9"))
        assertEquals("••••", maskSecret("abc"))
    }

}
