package de.sofahub.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentAppsTest {
    @Test fun `newest first without duplicates and limited`() {
        var list = emptyList<String>()
        (1..15).forEach { list = AppSettings.pushRecent(list, "app$it") }
        list = AppSettings.pushRecent(list, "app10")
        assertEquals(AppSettings.MAX_RECENT_APPS, list.size)
        assertEquals(listOf("app10", "app15", "app14"), list.take(3))
        assertEquals(1, list.count { it == "app10" })
    }
}
