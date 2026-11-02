package de.sofahub.app.feature.photos

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.feature.MainViewModel
import de.sofahub.app.ui.components.KeyCap
import de.sofahub.app.ui.components.Txt
import de.sofahub.app.ui.theme.Icon
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon
import kotlinx.coroutines.delay

/** Fernbedienbare Diashow: OK Pause/Weiter, ◀ ▶ blättern, Zurück zur Startseite. Kaputte Bilder werden übersprungen. */
@Composable
fun SlideshowScreen(vm: MainViewModel, s: AppSettings) {
    val photos = s.photos
    var index by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var overlay by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    if (photos.isEmpty()) {
        Box(Modifier.fillMaxSize().focusRequester(focus).focusable(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                de.sofahub.app.ui.components.EmptyArt(de.sofahub.app.ui.components.Illustration.Photos, 200)
                Txt("Keine Fotos vorhanden.", Sh.style(34, color = Sh.text2))
                Txt("Fotos über Einstellungen › Fotos & Notiz › Vom Handy übertragen hinzufügen.", Sh.style(23, color = Sh.hint))
            }
        }
        return
    }
    val current = photos[index.mod(photos.size)]
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, current) {
        value = vm.c.photos.load(current, 1920, 1080)?.asImageBitmap()
    }
    var failed by remember(current) { mutableStateOf(false) }
    LaunchedEffect(current) {
        delay(3000)
        if (bitmap == null) failed = true
    }
    LaunchedEffect(index, paused, failed) {
        if (paused) return@LaunchedEffect
        delay(if (failed) 1500 else s.slideshowSeconds * 1000L)
        index++
    }
    LaunchedEffect(overlay) {
        if (overlay > 0) {
            delay(2500)
            overlay = 0
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { paused = !paused; overlay++; true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { index++; overlay++; true }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { index--; overlay++; true }
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { overlay++; true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        Crossfade(bitmap, animationSpec = tween(700), label = "photo") { img ->
            if (img != null) Image(img, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else if (failed) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Txt("Bild nicht lesbar – wird übersprungen", Sh.style(28, color = Sh.hint)) }
        }
        AnimatedVisibility(overlay > 0 || paused, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 54.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xCC0A0A0A)).padding(horizontal = 28.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Icon(if (paused) ShIcon.Pause else ShIcon.Play, Sh.text, 26)
                Txt("${index.mod(photos.size) + 1} / ${photos.size}", Sh.style(22, color = Sh.text))
                KeyCap("◀ ▶"); Txt("Blättern", Sh.style(19, color = Sh.hint))
                KeyCap("OK"); Txt(if (paused) "Weiter" else "Pause", Sh.style(19, color = Sh.hint))
                KeyCap("↩"); Txt("Beenden", Sh.style(19, color = Sh.hint))
            }
        }
    }
}
