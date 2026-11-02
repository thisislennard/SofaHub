package de.sofahub.app.integration.homeassistant

import de.sofahub.app.core.model.HaError
import de.sofahub.app.core.storage.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Ergebnis der letzten Meldung an Home Assistant (für die Einstellungen). */
sealed interface PresenceStatus {
    data object Off : PresenceStatus
    data object Idle : PresenceStatus
    data class Ok(val epochMs: Long, val app: String, val screenOn: Boolean) : PresenceStatus
    data class Failed(val error: HaError) : PresenceStatus
}

/**
 * Meldet den Fire-TV-Zustand als zwei Entitäten an Home Assistant, damit dort Automationen darauf reagieren können:
 * - `binary_sensor.sofa_hub_fernseher` – an/aus (Bildschirm des Fire TV)
 * - `sensor.sofa_hub_app` – Name der App im Vordergrund („Sofa Hub“, „Netflix“ …, „Aus“)
 *
 * HA legt solche Entitäten über die REST-API an, vergisst sie aber bei einem Neustart; deshalb meldet der
 * Hintergrunddienst zusätzlich regelmäßig ([HEARTBEAT_MS]). Gleiche Zustände werden nicht doppelt gesendet.
 */
class HaPresenceReporter(
    private val settings: SettingsRepository,
    private val ha: HomeAssistantRepository,
    private val labelOf: (String) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var screenOn = true
    private var pkg: String? = null
    private var lastSent: Pair<Boolean, String>? = null
    private var lastSentAt = 0L
    private val state = MutableStateFlow<PresenceStatus>(PresenceStatus.Idle)
    val status: StateFlow<PresenceStatus> = state.asStateFlow()

    suspend fun onScreen(on: Boolean) = update { screenOn = on }

    suspend fun onApp(packageName: String) = update { pkg = packageName }

    /** Erneut melden, wenn die letzte Meldung länger her ist (HA-Neustart); bei ausgeschaltetem Fernseher seltener. */
    suspend fun heartbeat() = update(force = { now() - lastSentAt > if (screenOn) HEARTBEAT_MS else HEARTBEAT_OFF_MS })

    private suspend fun update(force: () -> Boolean = { false }, change: () -> Unit = {}) = mutex.withLock {
        change()
        val s = settings.current()
        if (!s.reportToHa || s.ha == null) {
            state.value = PresenceStatus.Off
            return@withLock
        }
        val app = if (!screenOn) "Aus" else appName(pkg)
        val key = screenOn to app
        if (key == lastSent && !force()) return@withLock
        val tv = ha.setStateDirect(
            TV_ENTITY, if (screenOn) "on" else "off",
            buildJsonObject {
                put("friendly_name", JsonPrimitive("Sofa Hub · Fernseher"))
                put("device_class", JsonPrimitive("power"))
                put("icon", JsonPrimitive("mdi:television"))
            },
        )
        val result = if (tv is HaResult.Err) tv else ha.setStateDirect(
            APP_ENTITY, app,
            buildJsonObject {
                put("friendly_name", JsonPrimitive("Sofa Hub · App"))
                put("icon", JsonPrimitive("mdi:application"))
                put("package", JsonPrimitive(if (screenOn) pkg.orEmpty() else ""))
            },
        )
        when (result) {
            is HaResult.Ok -> {
                lastSent = key
                lastSentAt = now()
                state.value = PresenceStatus.Ok(lastSentAt, app, screenOn)
            }
            is HaResult.Err -> state.value = PresenceStatus.Failed(result.error)
        }
    }

    private fun appName(p: String?): String = when {
        p == null -> "Sofa Hub"
        p == SOFA_HUB -> "Sofa Hub"
        p in LAUNCHERS -> "Fire-TV-Startseite"
        else -> labelOf(p) ?: p.substringAfterLast('.')
    }

    companion object {
        const val TV_ENTITY = "binary_sensor.sofa_hub_fernseher"
        const val APP_ENTITY = "sensor.sofa_hub_app"
        const val HEARTBEAT_MS = 10 * 60_000L
        const val HEARTBEAT_OFF_MS = 60 * 60_000L
        private const val SOFA_HUB = "de.sofahub.app"
        private val LAUNCHERS = setOf("com.amazon.tv.launcher")
    }
}
