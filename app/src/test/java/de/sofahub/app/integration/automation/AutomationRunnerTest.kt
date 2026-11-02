package de.sofahub.app.integration.automation

import de.sofahub.app.core.model.ActionKind
import de.sofahub.app.core.model.AppRule
import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.HaAction
import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.HaError
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.core.storage.SettingsRepository
import de.sofahub.app.integration.homeassistant.HaResult
import de.sofahub.app.integration.homeassistant.HomeAssistantRepository
import de.sofahub.app.integration.homeassistant.MediaAction
import de.sofahub.app.integration.homeassistant.ServiceCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSettings(var value: AppSettings) : SettingsRepository {
    override val settings = MutableStateFlow(value)
    override suspend fun current() = value
    override suspend fun update(transform: (AppSettings) -> AppSettings) = transform(value).also { value = it }
}

private class FakeHa(private val results: MutableList<HaResult<Unit>> = mutableListOf()) : HomeAssistantRepository {
    val calls = mutableListOf<ServiceCall>()
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
    override suspend fun callDirect(call: ServiceCall): HaResult<Unit> {
        calls += call
        return if (results.isEmpty()) HaResult.Ok(Unit) else results.removeAt(0)
    }
}

class AutomationRunnerTest {
    @Test fun `toCall maps kinds to services and rejects mismatches`() {
        assertEquals("scene" to "turn_on", AutomationRunner.toCall(HaAction("scene.kino", ActionKind.ACTIVATE))!!.let { it.domain to it.service })
        assertEquals("script" to "turn_on", AutomationRunner.toCall(HaAction("script.abend", ActionKind.ACTIVATE))!!.let { it.domain to it.service })
        assertEquals("light" to "turn_off", AutomationRunner.toCall(HaAction("light.wz", ActionKind.OFF))!!.let { it.domain to it.service })
        assertEquals("switch" to "turn_on", AutomationRunner.toCall(HaAction("switch.tv", ActionKind.ON))!!.let { it.domain to it.service })
        assertNull(AutomationRunner.toCall(HaAction("light.wz", ActionKind.ACTIVATE)))
        assertNull(AutomationRunner.toCall(HaAction("scene.kino", ActionKind.ON)))
        assertNull(AutomationRunner.toCall(HaAction("sensor.temp", ActionKind.OFF)))
        assertNull(AutomationRunner.toCall(HaAction("kaputt", ActionKind.OFF)))
    }

    @Test fun `power off runs all actions in order and records result`() = runTest {
        val ha = FakeHa()
        val s = FakeSettings(AppSettings(powerOffActions = listOf(HaAction("light.wz", ActionKind.OFF), HaAction("sensor.x", ActionKind.OFF), HaAction("switch.tv", ActionKind.OFF))))
        val r = AutomationRunner(s, ha) { 1000L }
        r.onPowerOff()
        assertEquals(listOf("light", "switch"), ha.calls.map { it.domain })
        val run = r.lastRun.value!!
        assertEquals(2, run.ok)
        assertEquals(1, run.failed)
    }

    @Test fun `power on retries network errors, power off does not`() = runTest {
        val actions = listOf(HaAction("light.wz", ActionKind.ON))
        val haOn = FakeHa(mutableListOf(HaResult.Err(HaError.Network), HaResult.Err(HaError.Timeout)))
        AutomationRunner(FakeSettings(AppSettings(powerOnActions = actions)), haOn).onPowerOn()
        assertEquals(3, haOn.calls.size)

        val haOff = FakeHa(mutableListOf(HaResult.Err(HaError.Network)))
        val r = AutomationRunner(FakeSettings(AppSettings(powerOffActions = actions)), haOff)
        r.onPowerOff()
        assertEquals(1, haOff.calls.size)
        assertEquals(1, r.lastRun.value!!.failed)
    }

    @Test fun `power on does not retry auth errors`() = runTest {
        val ha = FakeHa(mutableListOf(HaResult.Err(HaError.Unauthorized)))
        AutomationRunner(FakeSettings(AppSettings(powerOnActions = listOf(HaAction("light.wz", ActionKind.ON)))), ha).onPowerOn()
        assertEquals(1, ha.calls.size)
    }

    @Test fun `app rule fires once within debounce window`() = runTest {
        var t = 0L
        val ha = FakeHa()
        val s = FakeSettings(AppSettings(appRules = listOf(AppRule("com.netflix.ninja", listOf(HaAction("scene.kino", ActionKind.ACTIVATE))))))
        val r = AutomationRunner(s, ha) { t }
        assertTrue(r.onAppOpened("com.netflix.ninja"))
        t += 5_000
        assertFalse(r.onAppOpened("com.netflix.ninja"))
        t += AutomationRunner.APP_DEBOUNCE_MS
        assertTrue(r.onAppOpened("com.netflix.ninja"))
        assertEquals(2, ha.calls.size)
        assertFalse(r.onAppOpened("com.other.app"))
    }

    @Test fun `app rule without actions does nothing`() = runTest {
        val ha = FakeHa()
        val r = AutomationRunner(FakeSettings(AppSettings(appRules = listOf(AppRule("com.netflix.ninja")))), ha)
        assertFalse(r.onAppOpened("com.netflix.ninja"))
        assertTrue(ha.calls.isEmpty())
    }

    @Test fun `light with color sends rgb and brightness, describe names it`() {
        val a = HaAction("light.wz", ActionKind.ON, rgb = listOf(30, 90, 255), brightness = 25)
        val call = AutomationRunner.toCall(a)!!
        assertEquals("turn_on", call.service)
        assertEquals("[30,90,255]", call.data["rgb_color"].toString())
        assertEquals("25", call.data["brightness_pct"].toString())
        assertEquals("einschalten · Blau · 25 %", AutomationRunner.describe(a))
        val warm = AutomationRunner.toCall(HaAction("light.wz", ActionKind.ON, kelvin = 2700))!!
        assertEquals("2700", warm.data["color_temp_kelvin"].toString())
        assertNull(warm.data["rgb_color"])
        assertNull(AutomationRunner.toCall(HaAction("switch.tv", ActionKind.ON, rgb = listOf(1, 2, 3)))!!.data["rgb_color"])
    }
}
