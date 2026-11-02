package de.sofahub.app.integration.homeassistant

import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.HaConfig
import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.HaError
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.core.storage.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class Settings(var value: AppSettings) : SettingsRepository {
    override val settings = MutableStateFlow(value)
    override suspend fun current() = value
    override suspend fun update(transform: (AppSettings) -> AppSettings) = transform(value).also { value = it }
}

private class RecordingHa(var fail: HaError? = null) : HomeAssistantRepository {
    val posts = mutableListOf<Pair<String, String>>()
    override val snapshot: StateFlow<HaSnapshot> = MutableStateFlow(HaSnapshot())
    override fun start() = Unit
    override fun setForeground(foreground: Boolean) = Unit
    override fun setBackgroundWatch(ids: Set<String>) = Unit
    override fun reconnectNow() = Unit
    override suspend fun testConnection(address: String, token: String, allowHttp: Boolean): HaResult<List<HaEntity>> = error("unused")
    override suspend fun saveConnection(address: String, token: String, allowHttp: Boolean): HaResult<List<HaEntity>> = error("unused")
    override suspend fun disconnect() = Unit
    override fun setOn(entityId: String, on: Boolean) = Unit
    override fun setBrightness(entityId: String, percent: Int) = Unit
    override fun activate(entityId: String) = Unit
    override fun media(entityId: String, action: MediaAction) = Unit
    override fun selectSource(entityId: String, source: String) = Unit
    override suspend fun callDirect(call: ServiceCall): HaResult<Unit> = HaResult.Ok(Unit)
    override suspend fun setStateDirect(entityId: String, state: String, attributes: JsonObject): HaResult<Unit> {
        posts += entityId to state
        return fail?.let { HaResult.Err(it) } ?: HaResult.Ok(Unit)
    }
}

class HaPresenceTest {
    private val configured = AppSettings(ha = HaConfig("http://10.0.0.2:8123", allowHttp = true))

    @Test fun `reports screen and app, skips duplicates`() = runTest {
        val ha = RecordingHa()
        val r = HaPresenceReporter(Settings(configured), ha, labelOf = { if (it == "com.netflix.ninja") "Netflix" else null })
        r.onApp("com.netflix.ninja")
        r.onApp("com.netflix.ninja")
        assertEquals(listOf(HaPresenceReporter.TV_ENTITY to "on", HaPresenceReporter.APP_ENTITY to "Netflix"), ha.posts)
        r.onScreen(false)
        assertEquals(HaPresenceReporter.TV_ENTITY to "off", ha.posts[2])
        assertEquals(HaPresenceReporter.APP_ENTITY to "Aus", ha.posts[3])
    }

    @Test fun `heartbeat re-sends after interval only`() = runTest {
        var now = 0L
        val ha = RecordingHa()
        val r = HaPresenceReporter(Settings(configured), ha, labelOf = { null }, now = { now })
        r.onScreen(true)
        r.heartbeat()
        assertEquals(2, ha.posts.size)
        now += HaPresenceReporter.HEARTBEAT_MS + 1
        r.heartbeat()
        assertEquals(4, ha.posts.size)
    }

    @Test fun `switched off or not configured sends nothing, errors are kept`() = runTest {
        val ha = RecordingHa()
        val off = HaPresenceReporter(Settings(configured.copy(reportToHa = false)), ha, labelOf = { null })
        off.onScreen(true)
        assertTrue(ha.posts.isEmpty())
        assertEquals(PresenceStatus.Off, off.status.value)
        val failing = HaPresenceReporter(Settings(configured), RecordingHa(HaError.Forbidden), labelOf = { null })
        failing.onScreen(true)
        assertEquals(PresenceStatus.Failed(HaError.Forbidden), failing.status.value)
    }
}
