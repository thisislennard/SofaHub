package de.sofahub.app.kiosk

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import de.sofahub.app.feature.dashboard.Notice
import de.sofahub.app.ui.components.Island
import de.sofahub.app.ui.components.Toast
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon

/**
 * Zeigt Meldungen über anderen Apps (Netflix, YouTube, Spiegeln …) – genauso wie die Insel in Sofa Hub, oben mittig.
 * Das Fenster gehört der Bedienungshilfe (TYPE_ACCESSIBILITY_OVERLAY): keine Zusatzberechtigung nötig, nimmt nie den
 * Fokus und lässt sich nicht antippen. Meldungen erscheinen nacheinander, jeweils fünf Sekunden.
 */
class NoticeOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Notice>()
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    fun show(notice: Notice) = main.post {
        if (queue.size >= MAX_QUEUED) queue.removeFirst()
        queue.addLast(notice)
        if (view == null) next()
    }

    /** Alles sofort entfernen (Sofa Hub wieder vorn, Bildschirm aus, Dienst beendet). */
    fun clear() = main.post {
        main.removeCallbacksAndMessages(TOKEN)
        queue.clear()
        removeView()
    }

    private fun next() {
        val notice = queue.removeFirstOrNull() ?: return
        val visible = MutableTransitionState(false).apply { targetState = true }
        val scale = service.resources.displayMetrics.widthPixels / 1920f
        val o = OverlayOwner()
        val v = ComposeView(service).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(scale, 1f)) {
                    val toast = remember {
                        Toast(notice.text, notice.sub, if (notice.warn) Sh.coral else Sh.tealFill, if (notice.warn) ShIcon.Warning else ShIcon.Info)
                    }
                    AnimatedVisibility(
                        visibleState = visible,
                        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { -it / 2 },
                        exit = fadeOut(tween(250)) + slideOutVertically(tween(250)) { -it / 2 },
                    ) {
                        Island(null, toast, Modifier.padding(top = 58.dp))
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        runCatching { wm.addView(v, params) }.onFailure {
            android.util.Log.w("SofaHubNotice", "Overlay nicht möglich", it)
            o.destroy()
            return
        }
        view = v
        owner = o
        main.postDelayed({ visible.targetState = false }, TOKEN, SHOW_MS)
        main.postDelayed({ removeView(); next() }, TOKEN, SHOW_MS + 400)
    }

    private fun removeView() {
        view?.let { runCatching { wm.removeView(it) } }
        owner?.destroy()
        view = null
        owner = null
    }

    /** Minimaler Lebenszyklus für ComposeView außerhalb einer Activity. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        init {
            savedState.performRestore(Bundle())
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        val TOKEN = Any()
        const val SHOW_MS = 5_000L
        const val MAX_QUEUED = 5
    }
}
