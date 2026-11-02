package de.sofahub.app.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.sofahub.app.ui.theme.Sh

/**
 * Merkt sich pro Ansicht die Fokusziele und das zuletzt fokussierte Element, damit "Zurück" den Fokus auf der zuletzt
 * gewählten Kachel wiederherstellt.
 */
class FocusRegistry {
    private val requesters = mutableMapOf<String, FocusRequester>()
    var last: String? = null
    /** Scrollpositionen der Listen dieser Ansicht (Index, Versatz) – überlebt Unterseiten und Rückkehr. */
    val scroll = mutableMapOf<String, Pair<Int, Int>>()

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /** Fokus auf das zuletzt gewählte Element, sonst auf [default], sonst auf das erste verfügbare aus [fallbacks]. */
    fun restore(default: String, vararg fallbacks: String): Boolean {
        val keys = listOfNotNull(last, default) + fallbacks
        for (k in keys) {
            val r = requesters[k] ?: continue
            if (runCatching { r.requestFocus() }.isSuccess) return true
        }
        return false
    }

    fun restoreExact(key: String): Boolean {
        val r = requesters[key] ?: return false
        return runCatching { r.requestFocus() }.isSuccess
    }

    private var openDialogs = 0
    private var dialogOpener: String? = null
    private var restorePending = false
    private val restoreOpener = Runnable {
        restorePending = false
        if (openDialogs > 0) return@Runnable
        val key = dialogOpener ?: return@Runnable
        dialogOpener = null
        last = key
        focus(key)
    }

    /**
     * Ein Dialog öffnet sich. Wird er direkt aus einem anderen Dialog heraus geöffnet (Folgedialog), bleibt das
     * Rücksprungziel das Element, von dem aus der erste Dialog geöffnet wurde – nicht ein Knopf des alten Dialogs.
     */
    fun dialogOpened(opener: String?) {
        if (openDialogs == 0 && !restorePending) dialogOpener = opener
        openDialogs++
    }

    /** Ein Dialog schließt: kurz warten (ein Folgedialog kann noch erscheinen), dann zurück auf das Rücksprungziel. */
    fun dialogClosed() {
        openDialogs = (openDialogs - 1).coerceAtLeast(0)
        if (openDialogs == 0 && !restorePending) {
            restorePending = true
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(restoreOpener, 60)
        }
    }

    /** Anderes Rücksprungziel nach dem Dialog, z. B. wenn das öffnende Element gerade entfernt wurde. */
    fun returnFocusTo(key: String) {
        dialogOpener = key
    }

    fun focus(key: String) {
        requesters[key]?.let { runCatching { it.requestFocus() } }
    }
}

val LocalFocusRegistry = staticCompositionLocalOf { FocusRegistry() }

/**
 * Listenzustand, der seine Scrollposition in der [FocusRegistry] der Ansicht ablegt: Nach der Rückkehr von einer
 * Unterseite steht die Liste wieder an derselben Stelle, und der Fokus findet das zuletzt gewählte Element.
 */
@Composable
fun rememberRegistryListState(key: String): androidx.compose.foundation.lazy.LazyListState {
    val registry = LocalFocusRegistry.current
    val saved = registry.scroll[key]
    val state = remember(registry, key) { androidx.compose.foundation.lazy.LazyListState(saved?.first ?: 0, saved?.second ?: 0) }
    androidx.compose.runtime.DisposableEffect(state) {
        onDispose { registry.scroll[key] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
    }
    return state
}

/**
 * Knopfreihe: Kommt der Fokus von oben oder unten, landet er immer auf dem ersten Knopf ([firstKey]) – nicht auf dem,
 * der zufällig darüber liegt. Links/rechts innerhalb der Reihe bleibt normal.
 */
fun Modifier.enterFirst(registry: FocusRegistry, firstKey: String): Modifier = this
    .focusProperties {
        onEnter = {
            val dir = requestedFocusDirection
            if (dir == androidx.compose.ui.focus.FocusDirection.Down || dir == androidx.compose.ui.focus.FocusDirection.Up) {
                registry.requester(firstKey).requestFocus()
            }
        }
    }
    .focusGroup()

/** Fokus beim Erscheinen eines Dialogs fest auf [key] – unabhängig vom zuletzt gewählten Element dahinter. */
@Composable
fun FocusOnShow(key: String) {
    val registry = LocalFocusRegistry.current
    androidx.compose.runtime.LaunchedEffect(key) {
        repeat(10) {
            kotlinx.coroutines.delay(40)
            if (registry.restoreExact(key)) return@LaunchedEffect
        }
    }
}

/** Setzt beim Öffnen einer Ansicht den Fokus: zuletzt gewähltes Element, sonst [default]. */
@Composable
fun FocusOnEnter(default: String, vararg fallbacks: String) {
    val registry = LocalFocusRegistry.current
    androidx.compose.runtime.LaunchedEffect(registry) {
        // Einen Frame warten, bis alle Kacheln angehängt sind.
        repeat(5) {
            kotlinx.coroutines.delay(32)
            if (registry.restore(default, *fallbacks)) return@LaunchedEffect
        }
    }
}

private val focusEasing = CubicBezierEasing(.2f, .8f, .2f, 1f)
private val confirmKeys = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_BUTTON_A,
)

enum class TileStyle { Glass, Plain, Dashed, Solid }

/**
 * Fernbedienbare Kachel: Fokus = heller 4-px-Rahmen + Halo + Skalierung + Lichtreflex (nie nur Farbe).
 * OK löst [onClick] beim Loslassen aus, langes Drücken [onLongClick]. Ein Loslassen ohne vorheriges Drücken auf dieser
 * Kachel (z. B. nach einem Ansichtswechsel) wird ignoriert.
 */
@Composable
fun Tile(
    key: String,
    modifier: Modifier = Modifier,
    radius: Int = 28,
    style: TileStyle = TileStyle.Glass,
    background: Brush? = null,
    borderColor: Color? = null,
    focusScale: Float = Sh.focusScale,
    focusedBackground: Brush? = null,
    padding: PaddingValues = PaddingValues(0.dp),
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val registry = LocalFocusRegistry.current
    val requester = remember(registry, key) { registry.requester(key) }
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f, tween(200, easing = focusEasing), label = "scale")
    val glint by animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "glint")
    // Lichtkante: beim Fokussieren läuft einmal ein heller Schein schräg über die Kachel (wie Licht auf Glas).
    val sheen = remember { Animatable(1f) }
    LaunchedEffect(focused) {
        if (focused) {
            sheen.snapTo(0f)
            sheen.animateTo(1f, tween(900, delayMillis = 60, easing = FastOutSlowInEasing))
        }
    }
    val shape = RoundedCornerShape(radius.dp)

    // Fokus hellt Glas/Plain-Flächen auf; eigene Hintergründe (z. B. Verlauf der Spiegeln-Kachel) bleiben erhalten.
    val effectiveBase: Brush? = when {
        focused && (style == TileStyle.Glass || style == TileStyle.Plain) -> focusedBackground ?: background ?: Sh.glassFocused
        background != null -> background
        style == TileStyle.Glass -> Sh.glass
        else -> null
    }

    Box(
        modifier
            .zIndex(if (focused) 3f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                if (glint > 0f) {
                    val r = radius.dp.toPx()
                    val ring = 4.dp.toPx()
                    val halo = 6.dp.toPx()
                    drawRoundRect(
                        color = Sh.focusHalo.copy(alpha = Sh.focusHalo.alpha * glint),
                        topLeft = Offset(-ring - halo / 2, -ring - halo / 2),
                        size = Size(size.width + 2 * ring + halo, size.height + 2 * ring + halo),
                        cornerRadius = CornerRadius(r + ring + halo / 2),
                        style = Stroke(halo),
                    )
                    drawRoundRect(
                        color = Sh.focusRing.copy(alpha = glint),
                        topLeft = Offset(-ring / 2, -ring / 2),
                        size = Size(size.width + ring, size.height + ring),
                        cornerRadius = CornerRadius(r + ring / 2),
                        style = Stroke(ring),
                    )
                }
            }
            .clip(shape)
            .then(if (effectiveBase != null) Modifier.background(effectiveBase) else Modifier)
            .then(
                when (style) {
                    TileStyle.Glass -> Modifier.border(1.dp, borderColor ?: Sh.glassBorder, shape)
                    TileStyle.Dashed -> Modifier.drawBehind {
                        drawRoundRect(
                            color = borderColor ?: Sh.veil(0.220f),
                            cornerRadius = CornerRadius(radius.dp.toPx()),
                            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                        )
                    }
                    else -> if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier
                },
            )
            .drawWithContent {
                drawContent()
                if (glint > 0f && sheen.value < 1f) {
                    val w = size.width
                    val x = -w * .6f + (w * 2.2f) * sheen.value
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = .22f * glint), Color.Transparent),
                            start = Offset(x - w * .35f, 0f),
                            end = Offset(x + w * .1f, size.height),
                        ),
                    )
                }
                if (glint > 0f) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = .16f * glint), Color.Transparent),
                            center = Offset(size.width * .25f, -size.height * .1f),
                            radius = size.width * .9f,
                        ),
                    )
                }
            }
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) pressed = false
                if (it.isFocused) {
                    registry.last = key
                    onFocus?.invoke()
                }
            }
            .onPreviewKeyEvent { ev ->
                if (ev.nativeKeyEvent.keyCode !in confirmKeys) return@onPreviewKeyEvent false
                if (!enabled) return@onPreviewKeyEvent true
                when (ev.type) {
                    KeyEventType.KeyDown -> {
                        val native = ev.nativeKeyEvent
                        if (native.repeatCount == 0) {
                            pressed = true
                            longFired = false
                        } else if (pressed && !longFired && onLongClick != null && (native.isLongPress || native.repeatCount >= 8)) {
                            longFired = true
                            onLongClick()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (pressed && !longFired) onClick?.invoke()
                        pressed = false
                        longFired = false
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .semantics { role = Role.Button }
            .padding(padding),
    ) {
        content(focused)
    }
}
