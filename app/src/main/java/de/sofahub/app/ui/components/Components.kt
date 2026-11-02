package de.sofahub.app.ui.components

import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import de.sofahub.app.ui.theme.Icon
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon

@Composable
fun Txt(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) = BasicText(text, modifier, style, overflow = TextOverflow.Ellipsis, maxLines = maxLines)

/** Drei sehr weiche, statische Farbflächen hinter allen Ansichten; optional mit eigenem, abgedunkeltem Hintergrundbild. */
data class Ambient(val a: Color, val ax: Float, val ay: Float, val b: Color, val bx: Float, val by: Float, val c: Color, val cx: Float, val cy: Float) {
    /** Lichtflächen kommen aus dem aktiven Farbdesign; die Ansichten variieren nur die Anordnung. */
    companion object {
        val Main get() = Sh.theme.let { Ambient(it.ambientA, 0f, 0f, it.ambientB, 1f, 0f, it.ambientC, .6f, 1.25f) }
        val SmartHome get() = Sh.theme.let { Ambient(Color(0x24FFC94D), 0f, 0f, it.ambientB, 1f, 0f, it.ambientA, .6f, 1.25f) }
        val Mirror get() = Sh.theme.let { Ambient(it.ambientB, .22f, .5f, it.ambientA, 1f, 0f, it.ambientC, .8f, 1.25f) }
        val Settings get() = Sh.theme.let { Ambient(it.ambientA, 0f, 0f, it.ambientB, 1f, 0f, it.ambientC.copy(alpha = .12f), .6f, 1.25f) }
        val Updates get() = Sh.theme.let { Ambient(it.ambientC, 0f, 0f, it.ambientB, 1f, 0f, it.ambientA, .6f, 1.25f) }
    }
}

@Composable
fun Backdrop(ambient: Ambient, image: ImageBitmap?, dimPercent: Int, extra: (androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Sh.bg)) {
        // Bild und Ambient-Licht sind statisch, aber teuer (Vollbild-Verläufe): in einer eigenen, zwischengespeicherten
        // Ebene, damit Animationen darüber (Fokus, Wetter, Spinner) sie nicht bei jedem Bild neu rastern.
        Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Sh.bg.copy(alpha = dimPercent.coerceIn(0, 95) / 100f)))
        }
        Canvas(Modifier.fillMaxSize()) {
            fun blob(color: Color, fx: Float, fy: Float, rx: Float, ry: Float) {
                val center = Offset(size.width * fx, size.height * fy)
                withTransform({ scale(1f, ry / rx, center) }) {
                    // CSS: radial-gradient(rx ry at x y, color, transparent 62%)
                    drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center, rx * 0.62f), rx * 0.62f, center)
                }
            }
            blob(ambient.a, ambient.ax, ambient.ay, 900f, 650f)
            blob(ambient.b, ambient.bx, ambient.by, 900f, 650f)
            blob(ambient.c, ambient.cx, ambient.cy, 1100f, 600f)
            extra?.invoke(this)
        }
        }
        content()
    }
}

/**
 * Glasfläche ohne Fokus (Panels, Abschnitte). Beschneidet den Inhalt bewusst nicht: Fokusrahmen, Leuchtkante und
 * Vergrößerung fokussierter Kacheln ragen über den Kachelrand hinaus und wurden sonst am Panelrand abgeschnitten.
 */
fun Modifier.glass(radius: Int = 28): Modifier = this
    .background(Sh.glass, RoundedCornerShape(radius.dp))
    .border(1.dp, Sh.glassBorder, RoundedCornerShape(radius.dp))

@Composable
fun SwitchPill(on: Boolean, modifier: Modifier = Modifier) {
    val track by animateColorAsState(if (on) Sh.tealFill else Sh.veil(0.161f), tween(200), label = "track")
    val knob by animateDpAsState(if (on) 30.dp else 0.dp, spring(dampingRatio = .55f, stiffness = 500f), label = "knob")
    Box(modifier.size(68.dp, 38.dp).clip(RoundedCornerShape(19.dp)).background(track)) {
        Box(
            Modifier.padding(4.dp).offset(x = knob).size(30.dp).clip(CircleShape)
                .background(if (on) Color.White else Color(0xFFD9D3C8)),
        )
    }
}

@Composable
fun Spinner(size: Int = 30, stroke: Int = 3, color: Color = Sh.tealText) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "angle")
    Canvas(Modifier.size(size.dp)) {
        val w = stroke.dp.toPx()
        drawCircle(Sh.veil(0.180f), radius = this.size.minDimension / 2 - w / 2, style = Stroke(w))
        rotate(angle) {
            drawArc(color, -90f, 90f, false, topLeft = Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w), style = Stroke(w))
        }
    }
}

@Composable
fun IconChip(icon: ShIcon, fg: Color, bg: Color, size: Int = 58, iconSize: Int = 32, radius: Int = 18) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(radius.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, fg, iconSize)
    }
}

@Composable
fun Dot(color: Color, glow: Boolean = false, size: Int = 12) {
    Box(
        Modifier.size(size.dp)
            .then(if (glow) Modifier.drawBehind { drawCircle(color.copy(alpha = .35f), radius = this.size.minDimension) } else Modifier)
            .clip(CircleShape).background(color),
    )
}

/** Monogramm-Kachel für Apps; Farbe stabil aus dem Paketnamen abgeleitet. */
@Composable
fun Monogram(label: String, seed: String, size: Int = 50, fontSize: Int = 22, radius: Int = 14) {
    val tints = listOf(
        Color(0xFFFF5F6D) to Color(0xFFC81D3A), Color(0xFFFF9A4D) to Color(0xFFE5383B), Color(0xFF38BDF8) to Color(0xFF2563EB),
        Color(0xFFA78BFA) to Color(0xFF4F46E5), Color(0xFF22D3EE) to Color(0xFF0E7490), Color(0xFF4ADE80) to Color(0xFF15803D),
        Color(0xFFFDBA74) to Color(0xFFEA580C), Color(0xFFE879F9) to Color(0xFFA21CAF), Color(0xFFFB7185) to Color(0xFFBE123C),
        Color(0xFFFCD34D) to Color(0xFFD97706), Color(0xFF67E8F9) to Color(0xFF0891B2), Color(0xFF5EEAD4) to Color(0xFF0F766E),
    )
    val (a, b) = tints[Math.floorMod(seed.hashCode(), tints.size)]
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(radius.dp)).background(Brush.linearGradient(listOf(a, b))),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label.trim().firstOrNull()?.uppercase() ?: "?", Sh.style(fontSize, FontWeight.Bold, Color.White))
    }
}

@Composable
fun KeyCap(text: String) {
    Box(
        Modifier.height(34.dp).widthIn(min = 40.dp).clip(RoundedCornerShape(9.dp)).background(Sh.veil(0.090f))
            .border(1.dp, Sh.veil(0.122f), RoundedCornerShape(9.dp)).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, Sh.style(17, FontWeight.SemiBold, Sh.bright)) }
}

/** Tastenlegende unten; rechts optional ein gestrichelter Hinweis (z. B. "Demo · Beispieldaten"). */
@Composable
fun Footer(keys: List<Pair<String, String>>, badge: String? = null, middle: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            keys.forEach { (k, label) ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    KeyCap(k)
                    Txt(label, Sh.style(19, color = Sh.hint))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        middle?.invoke(this)
        if (badge != null) {
            Spacer(Modifier.width(24.dp))
            Box(
                Modifier.drawBehind {
                    drawRoundRect(
                        Sh.veil(0.220f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                        style = Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
                    )
                }.padding(horizontal = 14.dp, vertical = 6.dp),
            ) { Txt(badge, Sh.style(19, color = Sh.hint)) }
        }
    }
}

private val ISLAND_TEXT = Color(0xFFF4EFE6)
private val ISLAND_SOFT = Color(0xFFD8D2C7)
private val ISLAND_HINT = Color(0xFFA9A297)

enum class IslandTone { Ok, Busy, Warn, Neutral }

data class IslandStatus(val text: String, val tone: IslandTone)
data class Toast(val text: String, val sub: String? = null, val accent: Color = Sh.tealFill, val icon: ShIcon = ShIcon.Check, val id: Long = System.nanoTime())

/** "Insel" oben mittig: im Ruhezustand HA-Status, nach Aktionen kurz aufgeweitete Bestätigung. */
@Composable
fun Island(status: IslandStatus?, toast: Toast?, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = toast ?: status,
        transitionSpec = { fadeIn(tween(350, 180)) togetherWith fadeOut(tween(150)) },
        modifier = modifier,
        label = "island",
    ) { target ->
        when (target) {
            is Toast -> Row(
                Modifier.height(76.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFF0A0A0A))
                    .border(1.dp, Color(0x12FFFFFF), RoundedCornerShape(999.dp)).padding(start = 14.dp, end = 34.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(Modifier.size(50.dp).clip(CircleShape).background(target.accent), contentAlignment = Alignment.Center) {
                    Icon(target.icon, Color.White, 28)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Die Insel ist in jedem Design dunkel – Schrift daher immer hell.
                    Txt(target.text, Sh.style(26, FontWeight.SemiBold, ISLAND_TEXT))
                    target.sub?.let { Txt(it, Sh.style(18, color = ISLAND_HINT)) }
                }
            }
            is IslandStatus -> Row(
                Modifier.height(52.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFF0A0A0A))
                    .border(1.dp, Color(0x12FFFFFF), RoundedCornerShape(999.dp)).padding(start = 20.dp, end = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (target.tone) {
                    IslandTone.Busy -> Spinner(18, 3)
                    IslandTone.Ok -> Dot(Sh.teal, glow = true)
                    IslandTone.Warn -> Dot(Sh.coral, glow = true)
                    IslandTone.Neutral -> Dot(Sh.muted)
                }
                Txt(target.text, Sh.style(20, FontWeight.Medium, ISLAND_SOFT))
            }
            else -> Spacer(Modifier.height(52.dp))
        }
    }
}

/** Pillen-Button nach Design (Primär hell, Sekundär Glas). */
@Composable
fun ShButton(
    key: String,
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ShIcon? = null,
    trailingIcon: ShIcon? = null,
    height: Int = 68,
    fontSize: Int = 24,
    color: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Tile(
        key = key,
        modifier = modifier.height(height.dp),
        radius = height / 2,
        style = if (primary) TileStyle.Solid else TileStyle.Glass,
        background = if (primary) Brush.linearGradient(listOf(Sh.text, Sh.text)) else null,
        focusedBackground = if (primary) Brush.linearGradient(List(2) { if (Sh.light) Color.Black else Color.White }) else null,
        padding = PaddingValues(horizontal = (height / 2.2f).dp),
        enabled = enabled,
        onClick = onClick,
    ) {
        val fg = color ?: if (primary) Sh.onText else Sh.text
        Row(
            Modifier.align(Alignment.Center).then(if (enabled) Modifier else Modifier.scale(1f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon?.let { Icon(it, fg.copy(alpha = if (enabled) 1f else .4f), fontSize + 2) }
            Txt(text, Sh.style(fontSize, FontWeight.SemiBold, fg.copy(alpha = if (enabled) 1f else .4f)))
            trailingIcon?.let { Icon(it, fg, fontSize + 2) }
        }
    }
}

@Composable
fun BackButton(key: String = "back", onClick: () -> Unit) {
    Tile(key, Modifier.size(64.dp), radius = 32, onClick = onClick) {
        Box(Modifier.align(Alignment.Center)) { Icon(ShIcon.ChevronLeft, Sh.bright, 28) }
    }
}

/** Kopfzeile der Unterseiten: Zurück + Titel, rechts optionale Inhalte. */
@Composable
fun PageHeader(title: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
        BackButton(onClick = onBack)
        Spacer(Modifier.width(20.dp))
        Txt(title, Sh.style(46, Sh.W650, letterSpacing = (-0.03).em))
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** Modaler Dialog über abgedunkeltem Hintergrund. */
@Composable
fun ShDialog(
    title: String,
    body: String?,
    icon: ShIcon? = null,
    iconColor: Color = Sh.coralSoft,
    onDismiss: (() -> Unit)? = null,
    width: Int = 760,
    content: @Composable () -> Unit,
) {
    // Zurück schließt zuerst den Dialog; der Fokus bleibt im Dialog gefangen.
    androidx.activity.compose.BackHandler(enabled = onDismiss != null) { onDismiss?.invoke() }
    // Nach dem Schließen zurück auf das Element, von dem aus der Dialog geöffnet wurde – sonst springt der Fokus
    // an den Seitenanfang (z. B. auf den Zurück-Knopf).
    val registry = LocalFocusRegistry.current
    val opener = androidx.compose.runtime.remember { registry.last }
    androidx.compose.runtime.DisposableEffect(Unit) {
        registry.dialogOpened(opener)
        onDispose { registry.dialogClosed() }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000))
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(width.dp).clip(RoundedCornerShape(34.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF343238), Color(0xFF242327))))
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(34.dp))
                .padding(horizontal = 36.dp, vertical = 34.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                icon?.let { IconChip(it, iconColor, iconColor.copy(alpha = .2f), 52, 28, 16) }
                Txt(title, Sh.style(30, Sh.W650), maxLines = 2)
            }
            body?.let { Txt(it, Sh.style(22, color = Sh.text2, lineHeight = 1.4f), maxLines = 6) }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** Platzhalter in exakter Kachelgröße mit Schimmer – nichts springt beim Laden. */
@Composable
fun Skeleton(modifier: Modifier, radius: Int = 20) {
    val t = rememberInfiniteTransition(label = "sk")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "x")
    Box(
        modifier.clip(RoundedCornerShape(radius.dp)).background(Sh.veil(0.059f)).drawBehind {
            val w = size.width
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Sh.veil(0.090f), Color.Transparent), startX = w * x - w * .3f, endX = w * x + w * .3f))
        },
    )
}

/** Eckige Status-Kachel ohne Fokus (für "— nicht verfügbar"-Zustände). */
@Composable
fun SectionTitle(title: String, hint: String? = null) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Txt(title, Sh.style(30, Sh.W650))
        hint?.let { Txt(it, Sh.style(23, color = Sh.hint)) }
    }
}

val NoPadding = PaddingValues(0.dp)
