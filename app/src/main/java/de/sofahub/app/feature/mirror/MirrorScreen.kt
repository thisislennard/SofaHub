package de.sofahub.app.feature.mirror

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sofahub.app.feature.MainViewModel
import de.sofahub.app.integration.miracast.MiracastCapability
import de.sofahub.app.ui.components.FocusOnEnter
import de.sofahub.app.ui.components.Footer
import de.sofahub.app.ui.components.PageHeader
import de.sofahub.app.ui.components.ShButton
import de.sofahub.app.ui.components.Tile
import de.sofahub.app.ui.components.Txt
import de.sofahub.app.ui.components.glass
import de.sofahub.app.ui.theme.Icon
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon

/**
 * Spiegeln: Bereit (Direktstart möglich) oder Fehler (Direktstart gescheitert/nicht vorhanden) mit Ausweichweg.
 * Einen Zustand "verbunden" gibt es bewusst nicht: die App kann nicht feststellen, ob sich ein Sender verbindet.
 */
@Composable
fun MirrorScreen(vm: MainViewModel, failed: Boolean) {
    val capability by vm.miracast.collectAsStateWithLifecycle()
    val error = failed || capability == MiracastCapability.UNSUPPORTED
    val name = vm.deviceName ?: "diesen Fire TV"
    FocusOnEnter(if (error) "retry" else "start")
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 54.dp)) {
        PageHeader("Bildschirm spiegeln", onBack = { vm.back() })
        Row(Modifier.fillMaxWidth().weight(1f).padding(top = 30.dp), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            Column(Modifier.width(760.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Orb(error)
                Spacer(Modifier.height(18.dp))
                Txt(if (error) "FEHLER" else "BEREIT", Sh.style(24, FontWeight.SemiBold, if (error) Sh.warnText else Sh.tealText, letterSpacing = .08.em))
                Spacer(Modifier.height(12.dp))
                Txt(if (error) "Direktstart nicht möglich" else (vm.deviceName ?: "Fire TV"), Sh.style(52, Sh.W650, letterSpacing = (-0.035).em))
                Spacer(Modifier.height(14.dp))
                Txt(
                    if (error) "Fire OS hat den Spiegelungsempfang nicht geöffnet. Der Ausweichweg über die Home-Taste funktioniert trotzdem."
                    else "Öffne den Empfang und wähle „$name“ auf deinem Laptop oder Handy aus.",
                    Sh.style(27, color = Sh.text2, lineHeight = 1.35f).copy(textAlign = TextAlign.Center),
                    Modifier.width(640.dp), maxLines = 3,
                )
                Spacer(Modifier.height(40.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (error) {
                        ShButton("retry", "Erneut versuchen", primary = true, height = 72, fontSize = 25) { vm.startMirroring() }
                        ShButton("fallback", "Ausweichweg nutzen", height = 72, fontSize = 25) { vm.openMirrorFallback() }
                    } else {
                        ShButton("start", "Empfang öffnen", primary = true, icon = ShIcon.Cast, height = 72, fontSize = 25) { vm.startMirroring() }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
                Instructions(ShIcon.Laptop, "Windows 10 / 11", null, listOf("Drücke Win + K", "Wähle „$name“"))
                Instructions(
                    ShIcon.Phone, "Android", "nur Geräte mit Miracast",
                    listOf("Schnelleinstellungen herunterziehen", "„Smart View“, „Übertragen“ oder „Bildschirm teilen“", "„$name“ antippen"),
                )
                Tile(
                    "fallback-card", Modifier.fillMaxWidth(), radius = 32, borderColor = Color(0x47F0A274),
                    padding = PaddingValues(horizontal = 38.dp, vertical = 30.dp), focusScale = 1.03f,
                    onClick = { vm.openMirrorFallback() },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Color(0x29F0A274)), contentAlignment = Alignment.Center) {
                            Icon(ShIcon.Home, Sh.warnText, 34)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Txt("Klappt der direkte Aufruf nicht?", Sh.style(26, Sh.W650))
                            Txt("Home-Taste der Fernbedienung gedrückt halten und „Spiegeln“ wählen. OK öffnet „Display & Sounds“.", Sh.style(23, color = Sh.text2, lineHeight = 1.35f), maxLines = 2)
                        }
                        Icon(ShIcon.ChevronRight, Sh.hint, 30)
                    }
                }
                Txt("Kopiergeschützte Inhalte (DRM) werden oft nicht gespiegelt. Verzögerung hängt von WLAN und Sender ab.", Sh.style(20, color = Sh.hint), maxLines = 2)
            }
        }
        Spacer(Modifier.height(24.dp))
        Footer(listOf("OK" to "Auswählen", "↩" to "Zurück"))
    }
}

@Composable
private fun Instructions(icon: ShIcon, title: String, note: String?, steps: List<String>) {
    Column(Modifier.fillMaxWidth().glass(32).padding(horizontal = 38.dp, vertical = 34.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, Sh.bright, 34)
            Txt(title, Sh.style(30, Sh.W650))
            note?.let { Txt(it, Sh.style(20, color = Sh.hint)) }
        }
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            steps.forEachIndexed { i, step ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(Sh.veil(0.102f)), contentAlignment = Alignment.Center) {
                        Txt("${i + 1}", Sh.style(22, Sh.W650))
                    }
                    Txt(step, Sh.style(25, color = Sh.soft))
                }
            }
        }
    }
}

@Composable
private fun Orb(error: Boolean) {
    Box(Modifier.size(360.dp), contentAlignment = Alignment.Center) {
        if (!error) {
            val t = rememberInfiniteTransition(label = "wave")
            repeat(3) { i ->
                val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, delayMillis = 0, easing = androidx.compose.animation.core.LinearEasing), initialStartOffset = androidx.compose.animation.core.StartOffset(i * 1060)), label = "w$i")
                val a = if (p < .3f) p / .3f * .8f else .8f * (1f - (p - .3f) / .7f)
                Box(Modifier.fillMaxSize().scale(.55f + .45f * p).alpha(a).border(1.5.dp, Color(0x732DD4BF), CircleShape))
            }
        }
        Box(
            Modifier.size(200.dp).clip(CircleShape).background(
                if (error) Brush.radialGradient(listOf(Color(0xFFE79A6C), Color(0xFF9C4E2A)))
                else Brush.radialGradient(listOf(Color(0xFF7CF5E3), Color(0xFF14B8A6), Color(0xFF6D5DF5))),
            ),
            contentAlignment = Alignment.Center,
        ) { Icon(if (error) ShIcon.Warning else ShIcon.Cast, Color.White, if (error) 84 else 92) }
    }
}
