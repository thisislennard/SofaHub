package de.sofahub.app.integration.homeassistant

import de.sofahub.app.core.model.ActionStatus
import de.sofahub.app.core.model.HaArea
import de.sofahub.app.core.model.HaConnection
import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.HaError
import de.sofahub.app.core.model.HaSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Beispieldaten für den ausdrücklich eingeschalteten Demo-Modus. Die Oberfläche kennzeichnet ihn immer als "Demo";
 * Aktionen verändern nur diese lokalen Beispielzustände, nie echte Geräte.
 */
class DemoHomeAssistantRepository(private val scope: CoroutineScope) : HomeAssistantRepository {
    override val isDemo = true
    private val state = MutableStateFlow(initial())
    override val snapshot: StateFlow<HaSnapshot> = state.asStateFlow()

    override fun start() = Unit
    override fun setForeground(foreground: Boolean) = Unit
    override fun setBackgroundWatch(ids: Set<String>) = Unit
    override fun reconnectNow() = Unit
    override suspend fun testConnection(address: String, token: String, allowHttp: Boolean): HaResult<List<HaEntity>> =
        HaResult.Err(HaError.NotConnected)
    override suspend fun saveConnection(address: String, token: String, allowHttp: Boolean): HaResult<List<HaEntity>> =
        HaResult.Err(HaError.NotConnected)
    override suspend fun disconnect() = Unit

    private fun mutate(entityId: String, change: (HaEntity) -> HaEntity) {
        val e = state.value.entities[entityId] ?: return
        if (e.state == "unavailable") {
            state.update { it.copy(actions = it.actions + (entityId to ActionStatus.Failed(HaError.EntityUnavailable))) }
            return
        }
        state.update { it.copy(actions = it.actions + (entityId to ActionStatus.Pending)) }
        scope.launch {
            delay(700)
            state.update { s ->
                val cur = s.entities[entityId] ?: return@update s
                s.copy(entities = s.entities + (entityId to change(cur)), actions = s.actions - entityId)
            }
        }
    }

    override fun setOn(entityId: String, on: Boolean) = mutate(entityId) { it.copy(state = if (on) "on" else "off") }

    override fun setBrightness(entityId: String, percent: Int) = mutate(entityId) {
        if (percent <= 0) it.copy(state = "off")
        else it.copy(state = "on", attributes = JsonObject(it.attributes + ("brightness" to JsonPrimitive(HaServices.percentToBrightness(percent)))))
    }

    override fun activate(entityId: String) {
        if (entityId == "scene.demo_filmabend") setBrightness("light.demo_wohnzimmer", 20)
        state.update { it.copy(actions = it.actions + (entityId to ActionStatus.Acknowledged)) }
        scope.launch {
            delay(2500)
            state.update { it.copy(actions = it.actions - entityId) }
        }
    }

    override fun media(entityId: String, action: MediaAction) = Unit
    override fun selectSource(entityId: String, source: String) = Unit

    override suspend fun callDirect(call: ServiceCall): HaResult<Unit> {
        // Demo: nur die Beispielzustände ändern, nie echte Geräte.
        val id = call.data["entity_id"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return HaResult.Ok(Unit)
        when (call.service) {
            "turn_on" -> if (call.domain == "scene" || call.domain == "script") activate(id) else setOn(id, true)
            "turn_off" -> setOn(id, false)
        }
        return HaResult.Ok(Unit)
    }

    companion object {
        private const val NOW = "2026-10-31T19:41:00+00:00"

        private fun e(id: String, state: String, name: String, vararg attrs: Pair<String, Any>) = HaEntity(
            entityId = id,
            state = state,
            attributes = buildJsonObject {
                put("friendly_name", name)
                attrs.forEach { (k, v) ->
                    when (v) {
                        is Number -> put(k, v)
                        is Boolean -> put(k, v)
                        is List<*> -> put(k, buildJsonArray { v.forEach { add(JsonPrimitive(it.toString())) } })
                        else -> put(k, v.toString())
                    }
                }
            },
            lastChanged = NOW,
            lastUpdated = NOW,
        )

        val entities = listOf(
            e("light.demo_wohnzimmer", "on", "Deckenlicht", "brightness" to 204, "supported_color_modes" to listOf("brightness")),
            e("light.demo_stehlampe", "on", "Stehlampe", "brightness" to 102, "supported_color_modes" to listOf("brightness")),
            e("light.demo_led", "off", "LED-Leiste", "supported_color_modes" to listOf("brightness")),
            e("light.demo_leselampe", "unavailable", "Leselampe"),
            e("switch.demo_tv", "on", "TV-Steckdose", "current_power_w" to 64),
            e("switch.demo_konsole", "off", "Steckdose Konsole"),
            e("climate.demo_heizung", "heat", "Heizung", "current_temperature" to 21.5, "temperature" to 22.0, "hvac_action" to "heating"),
            e("binary_sensor.demo_fenster_sued", "off", "Fenster Süd", "device_class" to "window"),
            e("binary_sensor.demo_balkon", "on", "Balkontür", "device_class" to "door"),
            e("binary_sensor.demo_bad", "on", "Fenster Bad", "device_class" to "window"),
            e("sensor.demo_feuchte", "48", "Luftfeuchte", "unit_of_measurement" to "%", "device_class" to "humidity"),
            e("scene.demo_filmabend", "2026-10-30T20:00:00+00:00", "Filmabend"),
            e("weather.demo_home", "cloudy", "Zuhause", "temperature" to 11, "temperature_unit" to "°C"),
        ).associateBy { it.entityId }

        val areas = listOf("wohnen" to "Wohnzimmer", "kueche" to "Küche", "bad" to "Bad", "schlafen" to "Schlafzimmer", "buero" to "Büro")
            .associate { it.first to HaArea(it.first, it.second) }

        fun initial() = HaSnapshot(
            connection = HaConnection.Connected,
            entities = entities,
            areas = areas,
            entityAreas = mapOf(
                "light.demo_wohnzimmer" to "wohnen", "light.demo_stehlampe" to "wohnen", "light.demo_led" to "wohnen",
                "light.demo_leselampe" to "schlafen", "switch.demo_tv" to "wohnen", "switch.demo_konsole" to "wohnen",
                "climate.demo_heizung" to "wohnen", "binary_sensor.demo_fenster_sued" to "wohnen", "binary_sensor.demo_balkon" to "wohnen",
                "binary_sensor.demo_bad" to "bad", "sensor.demo_feuchte" to "bad",
            ),
            registryAvailable = true,
            lastSyncEpochMs = System.currentTimeMillis(),
            stale = false,
        )

        val demoRoles = mapOf(
            de.sofahub.app.core.model.Role.LIGHT to "light.demo_wohnzimmer",
            de.sofahub.app.core.model.Role.TV_PLUG to "switch.demo_tv",
            de.sofahub.app.core.model.Role.TEMPERATURE to "climate.demo_heizung",
            de.sofahub.app.core.model.Role.MOVIE_SCENE to "scene.demo_filmabend",
            de.sofahub.app.core.model.Role.WEATHER to "weather.demo_home",
        )
        val demoWindows = listOf("binary_sensor.demo_fenster_sued", "binary_sensor.demo_balkon", "binary_sensor.demo_bad")
        val demoSmartHome = listOf(
            "light.demo_stehlampe", "light.demo_led", "light.demo_leselampe", "switch.demo_tv", "switch.demo_konsole",
            "climate.demo_heizung", "binary_sensor.demo_fenster_sued", "binary_sensor.demo_balkon", "sensor.demo_feuchte",
        )
    }
}

/** Leitet an Demo- oder echte Implementierung weiter, je nach Demo-Schalter in den Einstellungen. */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchingHomeAssistantRepository(
    private val real: HomeAssistantRepository,
    private val demo: HomeAssistantRepository,
    private val demoEnabled: StateFlow<Boolean>,
    scope: CoroutineScope,
) : HomeAssistantRepository {
    private val active get() = if (demoEnabled.value) demo else real
    override val isDemo: Boolean get() = demoEnabled.value
    override val snapshot: StateFlow<HaSnapshot> = demoEnabled
        .flatMapLatest { if (it) demo.snapshot else real.snapshot }
        .stateIn(scope, SharingStarted.Eagerly, real.snapshot.value)

    override fun start() = real.start()
    override fun setForeground(foreground: Boolean) = real.setForeground(foreground)
    override fun setBackgroundWatch(ids: Set<String>) = real.setBackgroundWatch(ids)
    override fun reconnectNow() = real.reconnectNow()
    override suspend fun testConnection(address: String, token: String, allowHttp: Boolean) = real.testConnection(address, token, allowHttp)
    override suspend fun saveConnection(address: String, token: String, allowHttp: Boolean) = real.saveConnection(address, token, allowHttp)
    override suspend fun disconnect() = real.disconnect()
    override fun setOn(entityId: String, on: Boolean) = active.setOn(entityId, on)
    override fun setBrightness(entityId: String, percent: Int) = active.setBrightness(entityId, percent)
    override fun activate(entityId: String) = active.activate(entityId)
    override fun media(entityId: String, action: MediaAction) = active.media(entityId, action)
    override fun selectSource(entityId: String, source: String) = active.selectSource(entityId, source)
    override suspend fun callDirect(call: ServiceCall) = active.callDirect(call)
    override suspend fun cameraImage(entityId: String) = active.cameraImage(entityId)
    override suspend fun calendarEvents(entityIds: List<String>, start: String, end: String) = active.calendarEvents(entityIds, start, end)
    override suspend fun history(entityId: String, hours: Int) = active.history(entityId, hours)
    override suspend fun setStateDirect(entityId: String, state: String, attributes: kotlinx.serialization.json.JsonObject) =
        if (demoEnabled.value) HaResult.Ok(Unit) else real.setStateDirect(entityId, state, attributes)
}
