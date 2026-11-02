package de.sofahub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import de.sofahub.app.feature.AppRoot
import de.sofahub.app.feature.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!vm.back()) {
                    // Im Kiosk-Modus bleibt Sofa Hub vorn; abschalten lässt er sich in den Einstellungen.
                    if (vm.settings.value?.kioskMode == true) {
                        vm.toast("Kiosk-Modus aktiv", "Abschalten unter Einstellungen › Start & Kiosk", icon = de.sofahub.app.ui.theme.ShIcon.Info)
                        return
                    }
                    // Startseite: Zurück verlässt Sofa Hub wie jede TV-App (kein erzwungener Launcher-Ersatz).
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        setContent { AppRoot(vm) }
    }

    /**
     * Home-Taste (Kiosk-Dienst holt Sofa Hub nach vorn) oder Start über das App-Symbol: immer zur Startseite statt dorthin,
     * wo man zuletzt war (z. B. mitten in den Einstellungen).
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val fromLauncher = intent.action == android.content.Intent.ACTION_MAIN &&
            (intent.hasCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER) || intent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER))
        // Nur wenn Sofa Hub wirklich verdeckt war – ein Overlay des Startbildschirms über Sofa Hub setzt nichts zurück.
        if (wasStopped && (intent.getBooleanExtra(EXTRA_GO_HOME, false) || fromLauncher)) vm.goHome()
    }

    private var wasStopped = false

    override fun onStop() {
        super.onStop()
        wasStopped = true
    }

    companion object {
        const val EXTRA_GO_HOME = "de.sofahub.app.GO_HOME"
    }

    override fun onResume() {
        super.onResume()
        wasStopped = false
        // Rückkehr aus Streaming-App, Spiegelung oder Systemeinstellungen.
        vm.refreshPlatform()
    }
}
