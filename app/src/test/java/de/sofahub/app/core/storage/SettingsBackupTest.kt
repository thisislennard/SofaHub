package de.sofahub.app.core.storage

import de.sofahub.app.core.model.ActionKind
import de.sofahub.app.core.model.AppRule
import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.FavoriteApp
import de.sofahub.app.core.model.HaAction
import de.sofahub.app.core.model.HaConfig
import de.sofahub.app.core.model.PhotoRef
import de.sofahub.app.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    private val full = AppSettings(
        setupCompleted = true,
        ha = HaConfig("http://192.168.178.20:8123", allowHttp = true),
        roles = mapOf(Role.LIGHT to "light.wz"),
        favorites = listOf(FavoriteApp("com.netflix.ninja")),
        calendarEntities = listOf("calendar.muell"),
        appRules = listOf(AppRule("com.netflix.ninja", listOf(HaAction("scene.kino", ActionKind.ACTIVATE)))),
        note = "Hallo",
        colorTheme = "nacht",
        photos = listOf(PhotoRef(localFile = "a.img")),
        background = PhotoRef(localFile = "bg.img"),
        lastUpdateCheckEpochMs = 42,
    )

    @Test fun `round trip restores settings but keeps device files of target`() {
        val text = SettingsBackup.export(full, 1000)
        assertFalse("Fotos gehören nicht in die Sicherung", "a.img" in text || "bg.img" in text)
        assertFalse("token" in text.lowercase())
        val target = AppSettings(photos = listOf(PhotoRef(localFile = "z.img")), lastUpdateCheckEpochMs = 7)
        val restored = (SettingsBackup.import(text, target) as SettingsBackup.ImportResult.Ok).settings
        assertEquals(full.ha, restored.ha)
        assertEquals(full.roles, restored.roles)
        assertEquals(full.favorites, restored.favorites)
        assertEquals(full.calendarEntities, restored.calendarEntities)
        assertEquals(full.appRules, restored.appRules)
        assertEquals("nacht", restored.colorTheme)
        assertTrue(restored.setupCompleted)
        assertEquals(target.photos, restored.photos)
        assertEquals(null, restored.background)
        assertEquals(7L, restored.lastUpdateCheckEpochMs)
    }

    @Test fun `rejects foreign or broken files`() {
        assertTrue(SettingsBackup.import("kein json", AppSettings()) is SettingsBackup.ImportResult.Invalid)
        assertTrue(SettingsBackup.import("""{"format":"anders","settings":{}}""", AppSettings()) is SettingsBackup.ImportResult.Invalid)
        assertTrue(SettingsBackup.import("""{"format":"sofahub-settings"}""", AppSettings()) is SettingsBackup.ImportResult.Invalid)
    }
}
