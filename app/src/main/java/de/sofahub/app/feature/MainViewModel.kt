package de.sofahub.app.feature

import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onEach
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sofahub.app.container
import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.effectiveHomeTiles
import de.sofahub.app.core.model.FavoriteApp
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.core.model.PhotoRef
import de.sofahub.app.core.model.Role
import de.sofahub.app.integration.apps.LaunchResult
import de.sofahub.app.integration.miracast.MiracastCapability
import de.sofahub.app.integration.miracast.MiracastLaunch
import de.sofahub.app.ui.components.FocusRegistry
import de.sofahub.app.ui.components.Toast
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Route {
    val key: String

    data object Home : Route { override val key = "home" }
    data class SmartHome(val room: String? = null) : Route { override val key = "smarthome" }
    data class Mirror(val failed: Boolean = false) : Route { override val key = "mirror" }
    data object Favorites : Route { override val key = "favorites" }
    /** [subpage]: von dort zurückgekehrt – Auswahl landet wieder auf diesem Eintrag (Favoriten/Updates). */
    data class Settings(val section: SettingsSection = SettingsSection.HomeAssistant, val subpage: Route? = null) : Route { override val key = "settings" }
    data object Updates : Route { override val key = "updates" }
    data class Setup(val step: Int = 1) : Route { override val key = "setup$step" }
    data class Picker(val target: PickerTarget) : Route { override val key = "picker" }
    data object Slideshow : Route { override val key = "slideshow" }
    data object Pairing : Route { override val key = "pairing" }
    data object Printer : Route { override val key = "printer" }
}

enum class SettingsSection { HomeAssistant, Assignment, Home, Appearance, Photos, Automations, Kiosk, Info }

/** Wo eine Automations-Aktion hingehört. */
sealed interface AutomationSlot {
    data object PowerOff : AutomationSlot
    data object PowerOn : AutomationSlot
    data class App(val packageName: String) : AutomationSlot
}

sealed interface PickerTarget {
    data class ForRole(val role: Role) : PickerTarget
    data object Windows : PickerTarget
    data object Calendars : PickerTarget
    data object Notices : PickerTarget
    /** Kachel der Startseite wählen: [index] ersetzt eine vorhandene, null fügt eine neue hinzu. */
    data class ForTile(val index: Int?) : PickerTarget
    data object SceneBar : PickerTarget
    data object SmartHome : PickerTarget
    data class ForAction(val slot: AutomationSlot) : PickerTarget
    data object NewAppRule : PickerTarget
}

/** Ein ViewModel für Navigation, Rückmeldungen und app-weite Aktionen; Integrationslogik bleibt in den Repositories. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val c = app.container
    val settings: StateFlow<AppSettings?> = c.settingsState
    /**
     * HA-Zustand für die Oberfläche: höchstens alle 250 ms ein neuer Stand. Eine große Installation meldet viele
     * Sensoränderungen pro Sekunde – jede würde sonst die ganze Startseite neu aufbauen.
     */
    // StateFlow ist bereits „conflated“: ein langsamer Sammler bekommt nach der Pause direkt den neuesten Stand.
    val ha: StateFlow<HaSnapshot> = c.ha.snapshot
        .onEach { delay(250) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, c.ha.snapshot.value)
    val apps = c.apps.installed
    val updates = c.updates.updateState
    val pairing = c.pairing.pairingState

    private val stack = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = stack.asStateFlow()
    private val registries = mutableMapOf<String, FocusRegistry>()

    private val toastState = MutableStateFlow<Toast?>(null)
    val toast: StateFlow<Toast?> = toastState.asStateFlow()
    private var toastJob: Job? = null

    private val miracastState = MutableStateFlow(MiracastCapability.UNKNOWN)
    val miracast: StateFlow<MiracastCapability> = miracastState.asStateFlow()
    val deviceName: String? get() = c.miracast.deviceName()

    /** Zeitpunkt des letzten Szenen-Starts (Szenen haben keinen dauerhaften "An"-Zustand). */
    private val sceneStarted = MutableStateFlow<Long?>(null)
    val sceneStartedAt: StateFlow<Long?> = sceneStarted.asStateFlow()
    /** Zuletzt gestartete Szene (für „Gestartet“ auf genau dieser Kachel). */
    var lastSceneId: String? = null
        private set

    private var initialized = false

    // Vor init deklariert: init startet die Coroutines sofort (Main.immediate) und liest diese Felder.
    private val noticeQueue = kotlinx.coroutines.channels.Channel<de.sofahub.app.feature.dashboard.Notice>(capacity = 8, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    init {
        viewModelScope.launch {
            settings.collect { s ->
                if (s != null) de.sofahub.app.ui.theme.Sh.theme = de.sofahub.app.ui.theme.ColorThemes.byId(s.colorTheme)
                if (s != null && !initialized) {
                    initialized = true
                    stack.value = listOf(if (s.setupCompleted) Route.Home else Route.Setup(1))
                }
            }
        }
        refreshPlatform()
        viewModelScope.launch {
            updates.collect { if (it is de.sofahub.app.integration.updates.UpdateState.Installed) toast("Update installiert", "Sofa Hub ${it.versionName}") }
        }
        // War Home Assistant länger weg (Problem oben sichtbar), kurz melden, wenn die Verbindung wieder steht.
        viewModelScope.launch {
            var problemSince = 0L
            ha.collect { snap ->
                when (snap.connection) {
                    is de.sofahub.app.core.model.HaConnection.Retrying -> if (problemSince == 0L) problemSince = System.currentTimeMillis()
                    de.sofahub.app.core.model.HaConnection.Connected -> {
                        if (problemSince != 0L && System.currentTimeMillis() - problemSince >= ISLAND_PROBLEM_DELAY_MS) toast("Home Assistant wieder verbunden")
                        problemSince = 0L
                    }
                    de.sofahub.app.core.model.HaConnection.Connecting -> Unit
                    else -> problemSince = 0L
                }
            }
        }
        // Meldungen (Drucker, gewählte Entitäten) nacheinander kurz in der Statusinsel zeigen – solange Sofa Hub vorn ist;
        // sonst übernimmt der Hintergrunddienst (über der anderen App).
        viewModelScope.launch {
            c.notices.notices.collect { if (c.appForeground.value) noticeQueue.trySend(it) }
        }
        viewModelScope.launch {
            for (n in noticeQueue) {
                toast(n.text, n.sub, warn = n.warn, icon = if (n.warn) ShIcon.Warning else ShIcon.Info, durationMs = 5000)
                delay(5400)
            }
        }
    }

    fun registry(route: Route): FocusRegistry = registries.getOrPut(route.key) { FocusRegistry() }

    /** Bei Rückkehr aus Fremd-App/Spiegelung: installierte Apps und Fähigkeiten neu prüfen, HA neu abgleichen. */
    fun refreshPlatform() {
        viewModelScope.launch { c.apps.refresh() }
        miracastState.value = c.miracast.capability()
        c.updates.recheckPermission()
    }

    fun navigate(route: Route) {
        stack.update { it + route }
    }

    /** Aus einem Einstellungsbereich weiter navigieren; „Zurück“ landet wieder in diesem Bereich. */
    fun navigateFromSettings(section: SettingsSection, route: Route, subpage: Route? = null) {
        stack.update { s -> (if (s.lastOrNull() is Route.Settings) s.dropLast(1) + Route.Settings(section, subpage) else s) + route }
    }

    fun replace(route: Route) {
        stack.update { it.dropLast(1) + route }
    }

    fun resetTo(route: Route) {
        stack.value = listOf(route)
    }

    /** Zurück zur Startseite (Home-Taste); während der Ersteinrichtung bleibt alles, wie es ist. */
    fun goHome() {
        val s = stack.value
        if (s.firstOrNull() != Route.Home || s.size <= 1) return
        if (Route.Pairing in s) c.pairing.stop()
        registries.clear()
        stack.value = listOf(Route.Home)
    }

    /** @return false, wenn bereits auf der Startansicht (dann darf das System die App verlassen). */
    fun back(): Boolean {
        val s = stack.value
        if (s.size <= 1) return false
        if (s.last() == Route.Pairing) c.pairing.stop()
        stack.value = s.dropLast(1)
        return true
    }

    fun toast(text: String, sub: String? = null, warn: Boolean = false, icon: ShIcon? = null, durationMs: Long = 2800) {
        toastJob?.cancel()
        toastState.value = Toast(text, sub, if (warn) Sh.coral else Sh.tealFill, icon ?: if (warn) ShIcon.Warning else ShIcon.Check)
        toastJob = viewModelScope.launch {
            delay(durationMs)
            toastState.value = null
        }
    }


    /** Kacheln der Startseite ändern; beim ersten Mal wird die bisher abgeleitete Liste übernommen. */
    fun editHomeTiles(change: (MutableList<de.sofahub.app.core.model.HomeTile>) -> Unit) = updateSettings { s ->
        val list = s.effectiveHomeTiles().toMutableList()
        change(list)
        s.copy(homeTiles = list.take(AppSettings.MAX_HOME_TILES))
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { c.settings.update(transform) }
    }

    // --- Spiegeln ---
    fun startMirroring() {
        when (val r = c.miracast.launch()) {
            MiracastLaunch.Started -> {
                toast("Spiegeln wird geöffnet", "Fire-TV-Empfang")
                viewModelScope.launch { c.settings.update { it.copy(lastMirrorEpochMs = System.currentTimeMillis(), lastMirrorMinutes = 0) } }
            }
            MiracastLaunch.Unavailable, MiracastLaunch.PermissionDenied, is MiracastLaunch.Error -> {
                miracastState.value = c.miracast.capability()
                val top = stack.value.lastOrNull()
                if (top is Route.Mirror) replace(Route.Mirror(failed = true)) else navigate(Route.Mirror(failed = true))
                if (r is MiracastLaunch.PermissionDenied) toast("Direktstart nicht erlaubt", "Ausweichweg nutzen", warn = true)
            }
        }
    }

    fun openMirrorFallback() {
        if (!c.miracast.openFallbackSettings()) toast("Einstellungen nicht verfügbar", "Home-Taste lang drücken › Spiegeln", warn = true)
    }

    // --- Apps ---
    fun launchApp(fav: FavoriteApp, label: String) {
        when (c.apps.launch(fav.packageName)) {
            LaunchResult.Started -> viewModelScope.launch {
                c.settings.update { it.copy(recentApps = AppSettings.pushRecent(it.recentApps, fav.packageName)) }
                if (c.automations.onAppOpened(fav.packageName)) toast("$label gestartet", "Automation ausgeführt")
            }
            LaunchResult.NotInstalled -> toast("$label ist nicht installiert", "In „Favoriten bearbeiten“ entfernen", warn = true)
            LaunchResult.Disabled -> toast("$label ist deaktiviert", warn = true)
            LaunchResult.NoLaunchActivity, LaunchResult.Failed -> toast("$label lässt sich nicht starten", warn = true)
        }
    }

    // --- Home Assistant ---
    fun toggle(entityId: String, currentlyOn: Boolean, label: String) {
        c.ha.setOn(entityId, !currentlyOn)
        toast(if (!currentlyOn) "$label eingeschaltet" else "$label ausgeschaltet", "Wird übertragen …")
    }

    fun setBrightness(entityId: String, percent: Int) = c.ha.setBrightness(entityId, percent)

    fun startScene(entityId: String, label: String) {
        c.ha.activate(entityId)
        lastSceneId = entityId
        sceneStarted.value = System.currentTimeMillis()
        toast("$label gestartet", if (entityId.startsWith("script.")) "Skript läuft" else "Szene aktiviert", icon = ShIcon.Check)
    }

    fun media(entityId: String, action: de.sofahub.app.integration.homeassistant.MediaAction) = c.ha.media(entityId, action)

    fun reconnectHa() = c.ha.reconnectNow()

    fun disconnectHa() {
        viewModelScope.launch {
            c.ha.disconnect()
            toast("Verbindung getrennt", "Token gelöscht")
        }
    }

    // --- Automationen ---
    fun addAutomationAction(slot: AutomationSlot, action: de.sofahub.app.core.model.HaAction) = updateSettings { s ->
        when (slot) {
            AutomationSlot.PowerOff -> s.copy(powerOffActions = s.powerOffActions + action)
            AutomationSlot.PowerOn -> s.copy(powerOnActions = s.powerOnActions + action)
            is AutomationSlot.App -> s.copy(appRules = s.appRules.map { if (it.packageName == slot.packageName) it.copy(actions = it.actions + action) else it })
        }
    }

    fun replaceAutomationAction(slot: AutomationSlot, index: Int, action: de.sofahub.app.core.model.HaAction) = updateSettings { s ->
        fun List<de.sofahub.app.core.model.HaAction>.with() = mapIndexed { i, a -> if (i == index) action else a }
        when (slot) {
            AutomationSlot.PowerOff -> s.copy(powerOffActions = s.powerOffActions.with())
            AutomationSlot.PowerOn -> s.copy(powerOnActions = s.powerOnActions.with())
            is AutomationSlot.App -> s.copy(appRules = s.appRules.map { if (it.packageName == slot.packageName) it.copy(actions = it.actions.with()) else it })
        }
    }

    fun removeAutomationAction(slot: AutomationSlot, index: Int) = updateSettings { s ->
        fun List<de.sofahub.app.core.model.HaAction>.without() = filterIndexed { i, _ -> i != index }
        when (slot) {
            AutomationSlot.PowerOff -> s.copy(powerOffActions = s.powerOffActions.without())
            AutomationSlot.PowerOn -> s.copy(powerOnActions = s.powerOnActions.without())
            is AutomationSlot.App -> s.copy(appRules = s.appRules.map { if (it.packageName == slot.packageName) it.copy(actions = it.actions.without()) else it })
        }
    }

    fun addAppRule(packageName: String) = updateSettings { s ->
        if (s.appRules.any { it.packageName == packageName }) s else s.copy(appRules = s.appRules + de.sofahub.app.core.model.AppRule(packageName))
    }

    fun removeAppRule(packageName: String) = updateSettings { s -> s.copy(appRules = s.appRules.filterNot { it.packageName == packageName }) }

    /** Aktionen eines Slots sofort testweise ausführen. */
    fun testAutomation(slot: AutomationSlot) {
        viewModelScope.launch {
            when (slot) {
                AutomationSlot.PowerOff -> c.automations.onPowerOff()
                AutomationSlot.PowerOn -> c.automations.onPowerOn()
                is AutomationSlot.App -> {
                    val rule = c.settings.current().appRules.firstOrNull { it.packageName == slot.packageName }
                    if (rule != null) c.automations.runNow(rule.actions)
                }
            }
            val run = c.automations.lastRun.value
            if (run != null) toast("Test ausgeführt", "${run.ok} ok" + if (run.failed > 0) ", ${run.failed} fehlgeschlagen" else "", warn = run.failed > 0)
        }
    }

    // --- Fotos/Hintergrund ---
    fun addPickedPhotos(uris: List<Uri>, asBackground: Boolean) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val refs = uris.map { c.photos.persist(it) }
            if (asBackground) {
                c.settings.update { it.copy(background = refs.first()) }
                toast("Hintergrund gesetzt", if (!refs.first().persisted) "Zugriff gilt evtl. nur bis zum Neustart" else null)
            } else {
                c.settings.update { s -> s.copy(photos = (s.photos + refs).distinct().take(AppSettings.MAX_PHOTOS)) }
                toast("${refs.size} Foto(s) hinzugefügt")
            }
        }
    }

    fun removePhoto(ref: PhotoRef) {
        viewModelScope.launch {
            c.settings.update { it.copy(photos = it.photos - ref) }
            c.photos.delete(ref)
        }
    }

    fun clearBackground() {
        viewModelScope.launch {
            val old = c.settings.current().background
            c.settings.update { it.copy(background = null) }
            old?.let { c.photos.delete(it) }
            toast("Standard-Hintergrund")
        }
    }

    fun completeSetup() {
        updateSettings { it.copy(setupCompleted = true) }
        resetTo(Route.Home)
    }

    /** Vollständiges Zurücksetzen der App (Einstellungen, Token, Bilder). */
    fun resetApp() {
        viewModelScope.launch {
            c.ha.disconnect()
            val s = c.settings.current()
            (s.photos + listOfNotNull(s.background)).forEach { c.photos.delete(it) }
            c.settings.update { AppSettings() }
            resetTo(Route.Setup(1))
        }
    }
}
