package de.sofahub.app.feature.dashboard

import androidx.compose.ui.graphics.Color
import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.integration.homeassistant.EntityView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Farbe und Helligkeit (0…1) eines eingeschalteten Lichts, damit die Kachel so leuchtet wie die Lampe. */
data class LightGlow(val color: Color, val brightness: Float)

object LightLook {
    private val WARM = Color(0xFFFFD166)

    fun glow(e: HaEntity?): LightGlow? {
        if (e == null || e.domain != "light" || !EntityView.isOn(e)) return null
        val brightness = (e.attrDouble("brightness")?.div(255.0)?.toFloat() ?: 1f).coerceIn(.05f, 1f)
        val rgb = (e.attr("rgb_color") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toInt() }
        val color = when {
            rgb != null && rgb.size == 3 -> Color(rgb[0].coerceIn(0, 255), rgb[1].coerceIn(0, 255), rgb[2].coerceIn(0, 255))
            e.attrDouble("color_temp_kelvin") != null -> kelvin(e.attrDouble("color_temp_kelvin")!!)
            else -> WARM
        }
        return LightGlow(color, brightness)
    }

    /** Lichtfarbe als Symbolfarbe: auf dem hellen Design abgedunkelt, damit Gelb/Weiß lesbar bleiben. */
    fun readable(c: Color): Color = if (!de.sofahub.app.ui.theme.Sh.light) c else Color(red = c.red * .62f, green = c.green * .5f, blue = c.blue * .35f)

    /** Näherung Farbtemperatur → Farbe zwischen 2000 K (warm) und 6500 K (kalt). */
    fun kelvin(k: Double): Color {
        val f = ((k - 2000) / 4500).toFloat().coerceIn(0f, 1f)
        val warm = Color(0xFFFF9F45)
        val cold = Color(0xFFEAF2FF)
        return Color(
            red = warm.red + (cold.red - warm.red) * f,
            green = warm.green + (cold.green - warm.green) * f,
            blue = warm.blue + (cold.blue - warm.blue) * f,
        )
    }
}
