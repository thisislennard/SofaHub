package de.sofahub.app.integration.automation

import de.sofahub.app.core.model.ActionKind
import de.sofahub.app.core.model.HaAction
import de.sofahub.app.core.model.HaError
import de.sofahub.app.core.storage.SettingsRepository
import de.sofahub.app.integration.homeassistant.HaResult
import de.sofahub.app.integration.homeassistant.HaServices
import de.sofahub.app.integration.homeassistant.HomeAssistantRepository
import de.sofahub.app.integration.homeassistant.ServiceCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ergebnis des letzten Automationslaufs für die Anzeige in den Einstellungen. */
data class AutomationRun(val trigger: String, val epochMs: Long, val ok: Int, val failed: Int)

/**
 * Führt die vom Nutzer gewählten HA-Aktionen aus: beim Aus-/Einschalten des Fire TV und beim Öffnen von Apps.
 * Aktionen laufen nacheinander per REST (auch ohne Live-Verbindung). Szenen/Skripte werden nie wiederholt; nur beim
 * Einschalten wird kurz gewartet, bis das Netzwerk nach dem Standby wieder da ist.
 */
class AutomationRunner(
    private val settings: SettingsRepository,
    private val ha: HomeAssistantRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val last = MutableStateFlow<AutomationRun?>(null)
    val lastRun: StateFlow<AutomationRun?> = last.asStateFlow()
    private val lastAppTrigger = mutableMapOf<String, Long>()

    suspend fun onPowerOff() = run("Fire TV aus", settings.current().powerOffActions, retryNetwork = false)

    suspend fun onPowerOn() = run("Fire TV an", settings.current().powerOnActions, retryNetwork = true)

    /**
     * App wurde geöffnet (aus Sofa Hub oder anderswo). Mehrfachmeldungen innerhalb von [APP_DEBOUNCE_MS] lösen nur einmal
     * aus – Start aus Sofa Hub und Fenster-Ereignis kommen sonst doppelt an.
     */
    suspend fun onAppOpened(packageName: String): Boolean {
        val rule = settings.current().appRules.firstOrNull { it.packageName == packageName } ?: return false
        if (rule.actions.isEmpty()) return false
        synchronized(lastAppTrigger) {
            val t = now()
            val prev = lastAppTrigger[packageName]
            if (prev != null && t - prev < APP_DEBOUNCE_MS) return false
            lastAppTrigger[packageName] = t
        }
        run("App geöffnet", rule.actions, retryNetwork = false)
        return true
    }

    /** Testlauf aus den Einstellungen (ohne Entprellung). */
    suspend fun runNow(actions: List<HaAction>) = run("Test", actions, retryNetwork = false)

    private suspend fun run(trigger: String, actions: List<HaAction>, retryNetwork: Boolean) {
        if (actions.isEmpty()) return
        var ok = 0
        var failed = 0
        for (action in actions) {
            val call = toCall(action)
            if (call == null) {
                failed++
                continue
            }
            var result = ha.callDirect(call)
            var attempts = 0
            while (retryNetwork && result is HaResult.Err && result.error in RETRYABLE && attempts < 6) {
                delay(5_000)
                attempts++
                result = ha.callDirect(call)
            }
            if (result is HaResult.Ok) ok++ else failed++
        }
        last.value = AutomationRun(trigger, now(), ok, failed)
    }

    companion object {
        const val APP_DEBOUNCE_MS = 20_000L
        private val RETRYABLE = setOf(HaError.Network, HaError.Timeout)

        val SWITCHABLE_DOMAINS = setOf("light", "switch", "input_boolean", "fan", "media_player")
        val ACTIVATABLE_DOMAINS = setOf("scene", "script")

        /** Übersetzt eine gespeicherte Aktion in einen HA-Serviceaufruf; unpassende Kombinationen ergeben null. */
        fun toCall(action: HaAction): ServiceCall? {
            val domain = action.entityId.substringBefore('.', "")
            return when (action.kind) {
                ActionKind.ACTIVATE -> when (domain) {
                    "scene" -> HaServices.activateScene(action.entityId)
                    "script" -> HaServices.runScript(action.entityId)
                    else -> null
                }
                ActionKind.ON -> when {
                    domain !in SWITCHABLE_DOMAINS -> null
                    domain == "light" && action.hasLook -> HaServices.lightOn(action.entityId, action.rgb, action.kelvin, action.brightness)
                    else -> HaServices.turnOn(action.entityId)
                }
                ActionKind.OFF -> if (domain in SWITCHABLE_DOMAINS) HaServices.turnOff(action.entityId) else null
            }
        }

        /** Kurzbeschreibung einer Aktion, z. B. „einschalten · Blau · 40 %“. */
        fun describe(action: HaAction): String = listOfNotNull(
            label(action.kind),
            LightColors.nameOf(action),
            action.brightness?.let { "$it %" },
        ).joinToString(" · ")

        fun label(kind: ActionKind): String = when (kind) {
            ActionKind.ON -> "einschalten"
            ActionKind.OFF -> "ausschalten"
            ActionKind.ACTIVATE -> "starten"
        }
    }
}

/** Feste Farbauswahl für Lichter in Automationen (Fernbedienung statt Farbrad). */
object LightColors {
    data class Choice(val name: String, val rgb: List<Int>? = null, val kelvin: Int? = null)

    val all = listOf(
        Choice("Warmweiß", kelvin = 2700), Choice("Neutralweiß", kelvin = 4000), Choice("Kaltweiß", kelvin = 6000),
        Choice("Rot", listOf(255, 30, 20)), Choice("Orange", listOf(255, 120, 0)), Choice("Gelb", listOf(255, 200, 0)),
        Choice("Grün", listOf(40, 210, 70)), Choice("Türkis", listOf(0, 200, 190)), Choice("Blau", listOf(30, 90, 255)),
        Choice("Violett", listOf(140, 60, 255)), Choice("Pink", listOf(255, 40, 160)), Choice("Kino-Rot", listOf(180, 20, 30)),
    )
    val brightnessSteps = listOf(10, 25, 50, 75, 100)

    fun nameOf(a: HaAction): String? = when {
        a.rgb != null -> all.firstOrNull { it.rgb == a.rgb }?.name ?: "Farbe"
        a.kelvin != null -> all.firstOrNull { it.kelvin == a.kelvin }?.name ?: "${a.kelvin} K"
        else -> null
    }
}
