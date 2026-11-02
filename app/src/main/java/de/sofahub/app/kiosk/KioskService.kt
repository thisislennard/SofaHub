package de.sofahub.app.kiosk

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import de.sofahub.app.MainActivity
import de.sofahub.app.container
import de.sofahub.app.core.model.AppSettings

/**
 * Hintergrunddienst von Sofa Hub (Bedienungshilfe, einmalig per ADB aktiviert).
 *
 * - Kiosk-Modus: Fire OS erlaubt fremden Apps weder einen eigenen Startbildschirm noch den Amazon-eigenen „Custom Home“-Weg
 *   (Berechtigung signature|amazon, gemessen). Taucht der Amazon-Startbildschirm auf (Home-Taste, Einschalten), holt der
 *   Dienst Sofa Hub nach vorn – nur wenn der Kiosk-Modus eingeschaltet ist.
 * - Automationen: Bildschirm aus/an (Fire TV aus-/eingeschaltet) und das Öffnen von Apps lösen die gewählten HA-Aktionen aus.
 *
 * Ausgewertet wird nur, welche App im Vordergrund erscheint; keine Bildschirminhalte, keine Eingaben.
 */
class KioskService : AccessibilityService() {
    private var lastLaunch = 0L
    private var lastForeground: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var overlay: NoticeOverlay? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val c = applicationContext.container
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    c.screenOn = false
                    overlay?.clear()
                    c.updateBackgroundWatch()
                    // Netzwerk und CPU kurz wachhalten, bis die Aktionen raus sind (Standby folgt gleich danach).
                    val lock = (getSystemService(POWER_SERVICE) as PowerManager)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SofaHub:powerOff")
                        .apply { acquire(20_000) }
                    scope.launch {
                        try {
                            c.automations.onPowerOff()
                            c.presence.onScreen(false)
                        } finally {
                            if (lock.isHeld) lock.release()
                        }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    c.screenOn = true
                    if (!c.appForeground.value) c.updateBackgroundWatch()
                    scope.launch { c.automations.onPowerOn() }
                    scope.launch { c.presence.onScreen(true) }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Meldungen über anderen Apps: nur wenn Sofa Hub nicht vorn ist (sonst zeigt die Insel sie) und erwünscht.
        val c = applicationContext.container
        c.screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        overlay = NoticeOverlay(this)
        // Dienst erst jetzt (wieder) aktiv, Sofa Hub aber schon im Hintergrund: Beobachtung jetzt einrichten.
        if (!c.appForeground.value) c.updateBackgroundWatch()
        scope.launch(Dispatchers.Main) {
            c.notices.notices.collect { n ->
                if (!c.appForeground.value && c.screenOn && c.settingsState.value?.noticesOverApps == true) overlay?.show(n)
            }
        }
        scope.launch(Dispatchers.Main) { c.appForeground.collect { if (it) overlay?.clear() } }
        // Läuft auch direkt nach dem Einschalten des Sticks: Sofa Hub automatisch starten.
        if (kioskEnabled()) bringToFront()
        // Zustand regelmäßig an Home Assistant melden (HA vergisst über die API gesetzte Entitäten beim Neustart).
        scope.launch {
            val c = applicationContext.container
            c.presence.onScreen((getSystemService(POWER_SERVICE) as PowerManager).isInteractive)
            while (true) {
                kotlinx.coroutines.delay(60_000)
                c.presence.heartbeat()
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        overlay?.clear()
        scope.cancel()
        super.onDestroy()
    }

    private var mirrorSince = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Eigene Fenster ohne Activity (Meldungs-Overlay, Dialoge) sind kein App-Wechsel – sonst gälte Sofa Hub während
        // Netflix als vorn (Anwesenheit, Spiegel-Dauer, App-Regeln würden danach erneut auslösen).
        if (pkg == packageName && event.className?.toString() != MainActivity::class.java.name) return
        trackMirroring(pkg)
        if (pkg != lastForeground && !pkg.startsWith("com.android.systemui")) {
            scope.launch { applicationContext.container.presence.onApp(pkg) }
        }
        if (pkg in LAUNCHER_PACKAGES) {
            lastForeground = pkg
            if (kioskEnabled()) bringToFront()
            return
        }
        // App-Regeln: nur beim Wechsel in eine andere App, nicht bei jedem Dialog derselben App.
        if (pkg != lastForeground && pkg != packageName && !pkg.startsWith("com.android.systemui")) {
            lastForeground = pkg
            scope.launch {
                val c = applicationContext.container
                c.automations.onAppOpened(pkg)
                c.settings.update { it.copy(recentApps = AppSettings.pushRecent(it.recentApps, pkg)) }
            }
        } else if (pkg == packageName) {
            lastForeground = pkg
        }
    }

    /** Merkt sich Beginn und Dauer, solange der Fire-TV-Spiegelempfang im Vordergrund ist. */
    private fun trackMirroring(pkg: String) {
        val now = System.currentTimeMillis()
        if (pkg == MIRROR_PACKAGE && mirrorSince == 0L) {
            mirrorSince = now
            scope.launch { applicationContext.container.settings.update { it.copy(lastMirrorEpochMs = now, lastMirrorMinutes = 0) } }
        } else if (pkg != MIRROR_PACKAGE && mirrorSince != 0L && !pkg.startsWith("com.android.systemui")) {
            val minutes = ((now - mirrorSince) / 60_000).toInt()
            mirrorSince = 0L
            scope.launch { applicationContext.container.settings.update { it.copy(lastMirrorMinutes = minutes) } }
        }
    }

    override fun onInterrupt() = Unit

    private fun kioskEnabled(): Boolean = runCatching { applicationContext.container.settingsState.value?.kioskMode }.getOrNull()
        ?: KioskPrefs.cachedEnabled(this)

    private fun bringToFront() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 800) return
        lastLaunch = now
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ).putExtra(MainActivity.EXTRA_GO_HOME, true),
            )
        }
    }

    companion object {
        /** Amazon-Startbildschirm(e). Andere Apps (Einstellungen, Streaming) werden nie unterbrochen. */
        val LAUNCHER_PACKAGES = setOf("com.amazon.tv.launcher")

        fun component(context: Context) = ComponentName(context, KioskService::class.java)

        /** Ist der Dienst in den Bedienungshilfen aktiviert? (Fire OS hat dafür kein Menü – einmalig per ADB.) */
        fun isEnabled(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = component(context)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun adbCommand(context: Context): String =
            "adb shell settings put secure enabled_accessibility_services ${component(context).flattenToShortString()}"
    }
}

/**
 * Letzter bekannter Kiosk-Schalter in SharedPreferences: beim Start nach dem Einschalten ist DataStore evtl. noch nicht
 * geladen, der Dienst soll trotzdem sofort richtig entscheiden.
 */
object KioskPrefs {
    private const val FILE = "kiosk"
    fun cachedEnabled(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun store(context: Context, enabled: Boolean) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
}

private const val MIRROR_PACKAGE = "com.amazon.cast.sink"
