package de.sofahub.app

import de.sofahub.app.core.storage.SettingsBackup
import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.network.Http
import de.sofahub.app.core.storage.DataStoreSettingsRepository
import de.sofahub.app.core.storage.KeystoreSecretStore
import de.sofahub.app.core.storage.SettingsRepository
import de.sofahub.app.integration.apps.AndroidAppsRepository
import de.sofahub.app.integration.apps.AppsRepository
import de.sofahub.app.integration.homeassistant.DemoHomeAssistantRepository
import de.sofahub.app.integration.homeassistant.HaResult
import de.sofahub.app.integration.homeassistant.HomeAssistantRepository
import de.sofahub.app.integration.homeassistant.RealHomeAssistantRepository
import de.sofahub.app.integration.homeassistant.SwitchingHomeAssistantRepository
import de.sofahub.app.core.model.message
import de.sofahub.app.integration.miracast.FireTvMiracastController
import de.sofahub.app.integration.miracast.MiracastController
import de.sofahub.app.integration.pairing.PairingActions
import de.sofahub.app.integration.pairing.PairingServer
import de.sofahub.app.integration.photos.PhotoStore
import de.sofahub.app.integration.updates.ApkDownloader
import de.sofahub.app.integration.updates.ApkVerifier
import de.sofahub.app.integration.updates.GitHubReleaseClient
import de.sofahub.app.integration.updates.InstallerAdapter
import de.sofahub.app.integration.updates.UpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File

/** Einfache manuelle Abhängigkeitsverwaltung: eine Instanz pro Prozess. */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings: SettingsRepository = DataStoreSettingsRepository.create(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val settingsState: StateFlow<AppSettings?> = settings.settings.map<AppSettings, AppSettings?> { it }
        .stateIn(appScope, SharingStarted.Eagerly, null)
    private val demoEnabled: StateFlow<Boolean> = settings.settings.map { it.demoMode }.stateIn(appScope, SharingStarted.Eagerly, false)

    val secrets = KeystoreSecretStore(context)
    val ha: HomeAssistantRepository = SwitchingHomeAssistantRepository(
        real = RealHomeAssistantRepository(settings, secrets, appScope, Http.base),
        demo = DemoHomeAssistantRepository(appScope),
        demoEnabled = demoEnabled,
        scope = appScope,
    )
    val apps: AppsRepository = AndroidAppsRepository(context)
    val automations = de.sofahub.app.integration.automation.AutomationRunner(settings, ha)
    val presence = de.sofahub.app.integration.homeassistant.HaPresenceReporter(settings, ha, labelOf = { pkg ->
        apps.installed.value.firstOrNull { it.packageName == pkg }?.label
            ?: runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
    })
    val haDiscovery = de.sofahub.app.integration.homeassistant.HaDiscovery(context)
    val miracast: MiracastController = FireTvMiracastController(context)
    val photos = PhotoStore(context)
    val notices = de.sofahub.app.feature.dashboard.NoticeCenter(ha, settingsState, appScope)

    /** Sofa Hub sichtbar (sonst zeigt der Hintergrunddienst Meldungen über der anderen App). */
    val appForeground = kotlinx.coroutines.flow.MutableStateFlow(false)
    @Volatile var screenOn = true

    /**
     * Im Hintergrund nur die Entitäten weiter beobachten, die Meldungen auslösen können – und nur, wenn Meldungen über
     * anderen Apps erwünscht sind, der Hintergrunddienst läuft und der Fernseher an ist. Sonst trennt HA wie bisher.
     */
    fun updateBackgroundWatch() {
        val s = settingsState.value
        val wanted = s != null && s.noticesOverApps && screenOn && !ha.isDemo && de.sofahub.app.kiosk.KioskService.isEnabled(context)
        ha.setBackgroundWatch(if (wanted) notices.watchIds(ha.snapshot.value, s) else emptySet())
    }

    private val userAgent = "SofaHub/${BuildConfig.VERSION_NAME} (Fire TV; +https://github.com/${BuildConfig.UPDATE_REPO})"
    private val githubHttp = Http.githubClient(GitHubReleaseClient.ALLOWED_HOSTS)
    /** Nur lesender Fine-grained-Token für das private Update-Repository (vom Nutzer eingegeben, verschlüsselt). */
    val githubTokens = KeystoreSecretStore(context, "github_token.bin", "sofahub_github_token")
    val github = GitHubReleaseClient(BuildConfig.UPDATE_REPO, userAgent, githubHttp, token = {
        (githubTokens.readToken() as? de.sofahub.app.core.storage.TokenResult.Present)?.token
    })

    /** Prüft den Token gegen das Repository und speichert ihn nur bei Erfolg. */
    suspend fun saveGitHubToken(token: String): Pair<Boolean, String> {
        val clean = token.trim()
        if (clean.isEmpty()) return false to "Bitte einen Token eingeben"
        return when (github.checkToken(clean)) {
            de.sofahub.app.integration.updates.TokenCheck.Ok -> {
                githubTokens.saveToken(clean)
                true to "GitHub-Token gespeichert"
            }
            de.sofahub.app.integration.updates.TokenCheck.Invalid -> false to "Token ungültig oder abgelaufen"
            de.sofahub.app.integration.updates.TokenCheck.NoAccess -> false to "Token hat keinen Lesezugriff auf ${BuildConfig.UPDATE_REPO}"
            de.sofahub.app.integration.updates.TokenCheck.Network -> false to "GitHub nicht erreichbar – Internetverbindung prüfen"
        }
    }

    val updates = UpdateRepository(
        settings = settings,
        github = github,
        downloader = ApkDownloader(githubHttp, File(context.cacheDir, "updates"), userAgent),
        verifier = ApkVerifier(context),
        installer = InstallerAdapter(context),
        scope = appScope,
        savedOfferFile = File(context.cacheDir, "updates/offer.json"),
    )

    val pairing = PairingServer(object : PairingActions {
        override suspend fun applyHomeAssistant(address: String, token: String, allowHttp: Boolean): Pair<Boolean, String> =
            when (val r = ha.saveConnection(address, token, allowHttp)) {
                is HaResult.Ok -> true to "Verbunden – ${r.value.size} Entitäten gefunden. Geräte jetzt am Fernseher auswählen."
                is HaResult.Err -> false to r.error.message()
            }

        override suspend fun applyGitHubToken(token: String): Pair<Boolean, String> = saveGitHubToken(token)

        override suspend fun applyNote(note: String): Pair<Boolean, String> {
            val clean = note.take(AppSettings.MAX_NOTE_LENGTH)
            settings.update { it.copy(note = clean) }
            return true to if (note.length > clean.length) "Notiz gekürzt gespeichert" else "Notiz gespeichert"
        }

        override suspend fun applyGuestWifi(ssid: String, password: String, security: String): Pair<Boolean, String> {
            val sec = de.sofahub.app.core.model.WifiSecurity.entries.firstOrNull { it.name == security } ?: de.sofahub.app.core.model.WifiSecurity.WPA
            val wifi = de.sofahub.app.core.model.GuestWifi(ssid.trim(), if (sec == de.sofahub.app.core.model.WifiSecurity.NONE) "" else password, sec)
            wifi.problem()?.let { return false to it }
            settings.update { it.copy(guestWifi = wifi) }
            return true to "Gäste-WLAN gespeichert – die Kachel steht auf der Startseite"
        }

        override suspend fun exportSettings(): String = SettingsBackup.export(settings.current(), System.currentTimeMillis())

        override suspend fun importSettings(json: String): Pair<Boolean, String> {
            val before = settings.current()
            return when (val r = SettingsBackup.import(json, before)) {
                is SettingsBackup.ImportResult.Invalid -> false to r.message
                is SettingsBackup.ImportResult.Ok -> {
                    settings.update { r.settings }
                    val newHa = r.settings.ha?.baseUrl != null && r.settings.ha.baseUrl != before.ha?.baseUrl
                    true to if (newHa) "Einstellungen wiederhergestellt – bitte den Home-Assistant-Token oben neu eingeben" else "Einstellungen wiederhergestellt"
                }
            }
        }

        override suspend fun applyImage(bytes: ByteArray, asBackground: Boolean): Pair<Boolean, String> {
            val ref = photos.saveUpload(bytes) ?: return false to "Bild konnte nicht gelesen werden"
            if (asBackground) {
                val old = settings.current().background
                settings.update { it.copy(background = ref) }
                old?.takeIf { it.localFile != null }?.let { photos.delete(it) }
                return true to "Hintergrund gesetzt"
            }
            var added = false
            settings.update {
                if (it.photos.size >= AppSettings.MAX_PHOTOS) it else { added = true; it.copy(photos = it.photos + ref) }
            }
            if (!added) photos.delete(ref)
            return if (added) true to "Foto hinzugefügt" else false to "Maximal ${AppSettings.MAX_PHOTOS} Fotos"
        }
    })
}

class SofaHubApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.ha.start()
        container.updates.onAppStart()
        // Kiosk-Schalter zusätzlich synchron ablegen: der Kiosk-Dienst braucht ihn direkt nach dem Einschalten.
        container.appScope.launch {
            container.settings.settings.collect { de.sofahub.app.kiosk.KioskPrefs.store(this@SofaHubApp, it.kioskMode) }
        }
        container.appScope.launch {
            container.settings.settings.map { it.noticesOverApps to it.noticeEntities }.distinctUntilChanged().collect {
                if (!container.appForeground.value) container.updateBackgroundWatch()
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.appForeground.value = true
                container.ha.setForeground(true)
            }
            override fun onStop(owner: LifecycleOwner) {
                container.appForeground.value = false
                // Ziel festlegen, solange der vollständige Stand (inkl. Drucker) noch da ist – dann erst umschalten.
                container.updateBackgroundWatch()
                container.ha.setForeground(false)
                // Einrichtungsdienst nie im Hintergrund weiterlaufen lassen.
                container.pairing.stop()
            }
        })
    }
}

val Context.container: AppContainer get() = (applicationContext as SofaHubApp).container
