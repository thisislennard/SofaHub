package de.sofahub.app.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.sofahub.app.R

/** Design-Tokens. Alle Maße in Design-Pixeln der 1920×1080-Basis (siehe [DesignScale]). */
object Sh {
    /** Aktives Farbdesign (Compose-State: Wechsel zeichnet alle Ansichten neu). */
    var theme by mutableStateOf(ColorThemes.Anthrazit)

    val bg get() = theme.bg
    val surface get() = theme.surface
    val light get() = theme.light

    /** Überlagerung für Flächen, Linien und Spuren: hell auf dunklen Designs, dunkel auf dem hellen Design. */
    fun veil(alpha: Float): Color = (if (light) Color(0xFF1A1917) else Color(0xFFFFF7EB)).copy(alpha = alpha)

    val glassTop get() = if (light) Color.White.copy(alpha = .78f) else veil(.075f)
    val glassBottom get() = if (light) Color.White.copy(alpha = .58f) else veil(.035f)
    val glassBorder get() = if (light) veil(.09f) else veil(.085f)
    val focusTop get() = if (light) Color.White else veil(.13f)
    val focusBottom get() = if (light) Color.White.copy(alpha = .9f) else veil(.06f)

    val text get() = if (light) Color(0xFF1A1917) else Color(0xFFF4EFE6)
    val text2 get() = if (light) Color(0xFF4A453F) else Color(0xFFC9C2B7)
    val text3 get() = if (light) Color(0xFF57524B) else Color(0xFFB8B1A6)
    val hint get() = if (light) Color(0xFF6B655C) else Color(0xFFA9A297)
    val muted get() = if (light) Color(0xFF7D776E) else Color(0xFF8F887D)
    val soft get() = if (light) Color(0xFF3A3631) else Color(0xFFD8D2C7)
    val bright get() = if (light) Color(0xFF24211E) else Color(0xFFE9E3D8)
    val ink = Color(0xFF1A1917)
    /** Schrift auf einer Fläche in Textfarbe (z. B. Primärknopf, gewählter Chip). */
    val onText get() = if (light) Color.White else ink

    val teal get() = theme.accent
    val tealText get() = theme.accentText
    val tealFill get() = theme.accentFill
    val sun = Color(0xFFFFD166)
    val coral = Color(0xFFFF7D6B)
    val coralText get() = if (light) Color(0xFFC2412D) else Color(0xFFFF9A7A)
    val coralSoft = Color(0xFFFFA093)
    val orange = Color(0xFFFFA24C)
    val orangeText get() = if (light) Color(0xFFC2410C) else Color(0xFFFFB56B)
    val violet = Color(0xFFA78BFA)
    val violetText get() = if (light) Color(0xFF6D28D9) else Color(0xFFC4B5FD)
    val sky = Color(0xFF60A5FA)
    val skyText get() = if (light) Color(0xFF1D4ED8) else Color(0xFF93C5FD)
    /** Text gefährlicher Aktionen (Entfernen, Zurücksetzen). */
    val dangerText get() = if (light) Color(0xFFB93815) else Color(0xFFF3B38E)
    val warnText get() = if (light) Color(0xFFB45309) else Color(0xFFF0A274)

    val focusRing get() = if (light) Color(0xFF1A1917) else Color(0xFFF7F2E8)
    val focusHalo get() = focusRing.copy(alpha = if (light) .14f else .10f)
    const val focusScale = 1.045f

    val glass get() = Brush.verticalGradient(listOf(glassTop, glassBottom))
    val glassFocused get() = Brush.verticalGradient(listOf(focusTop, focusBottom))
    val logo get() = Brush.linearGradient(listOf(theme.logoStart, theme.logoEnd))

    fun chip(color: Color, alpha: Float = .22f) = color.copy(alpha = alpha)

    @OptIn(ExperimentalTextApi::class)
    private fun geist(weight: Int) = Font(R.font.geist_variable, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

    @OptIn(ExperimentalTextApi::class)
    private fun geistMono(weight: Int) = Font(R.font.geist_mono_variable, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

    val font = FontFamily(geist(300), geist(400), geist(500), geist(600), geist(650), geist(700))
    val mono = FontFamily(geistMono(400), geistMono(500), geistMono(600))

    val W650 = FontWeight(650)

    /** Textstil in Design-Pixeln; Zeilenhöhe eng wie im Entwurf. */
    fun style(
        size: Int,
        weight: FontWeight = FontWeight.Normal,
        color: Color = text,
        letterSpacing: TextUnit = (-0.01).em,
        lineHeight: Float = 1.15f,
        mono: Boolean = false,
    ) = TextStyle(
        fontFamily = if (mono) this.mono else font,
        fontSize = size.sp,
        fontWeight = weight,
        color = color,
        letterSpacing = letterSpacing,
        lineHeight = (size * lineHeight).sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )
}

/**
 * Skaliert die Oberfläche so, dass 1 dp = 1 Design-Pixel der 1920×1080-Basis entspricht – unabhängig von der
 * tatsächlichen Bildschirmgröße (Fire TV meldet 1920×1080 bei 320 dpi). Schriftgröße folgt nicht der System-Skalierung,
 * damit das Raster ohne Scrollen passt.
 */
@Composable
fun DesignScale(content: @Composable () -> Unit) {
    BoxWithConstraints {
        val px = with(LocalDensity.current) { constraints.maxWidth.toFloat() }
        val heightPx = constraints.maxHeight.toFloat()
        // Bei anderem Seitenverhältnis die engere Achse nehmen, damit nichts abgeschnitten wird.
        val scale = minOf(px / 1920f, heightPx / 1080f)
        CompositionLocalProvider(LocalDensity provides Density(density = scale, fontScale = 1f)) {
            content()
        }
    }
}
