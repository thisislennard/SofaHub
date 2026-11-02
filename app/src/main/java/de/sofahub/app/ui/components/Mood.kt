package de.sofahub.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import de.sofahub.app.ui.theme.Sh
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Tageszeit-Stimmung: 0 tagsüber, steigt ab 18 Uhr auf bis zu 1 um 22 Uhr und fällt bis 6 Uhr wieder ab. */
fun eveningWarmth(hour: Int, minute: Int): Float {
    val h = hour + minute / 60f
    return when {
        h >= 18f -> ((h - 18f) / 4f).coerceAtMost(1f)
        h < 6f -> (1f - h / 6f).coerceAtLeast(0f)
        else -> 0f
    }
}

/** Begrüßung nach Tageszeit. */
fun greeting(hour: Int): String = when (hour) {
    in 5..10 -> "Guten Morgen"
    in 11..17 -> "Guten Tag"
    in 18..22 -> "Guten Abend"
    else -> "Gute Nacht"
}

/** Warmer, abgedunkelter Schleier am Abend – Fernseher blendet weniger. */
@Composable
fun EveningTint(warmth: Float) {
    if (warmth <= 0.01f) return
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFFF8A3D).copy(alpha = .07f * warmth), Color(0xFF3A1A00).copy(alpha = .10f * warmth))))
    }
}

private enum class Mood { Rain, Snow, Sun, Stars, Clouds, None }

private fun moodOf(state: String?): Mood = when (state) {
    "rainy", "pouring", "lightning-rainy", "hail" -> Mood.Rain
    "snowy", "snowy-rainy" -> Mood.Snow
    "sunny" -> Mood.Sun
    "clear-night" -> Mood.Stars
    "cloudy", "partlycloudy", "fog", "windy", "windy-variant" -> Mood.Clouds
    else -> Mood.None
}

/**
 * Sehr zurückhaltende Wetter-Animation hinter der Startseite (Zustand der HA-Wetter-Entität).
 * Leistung: Wolken und Sonne sind große Verläufe und stehen still (einmal gezeichnet, gecacht); nur Regen, Schnee
 * und Sterne bewegen sich, mit ~15 Bildern pro Sekunde.
 */
@Composable
fun WeatherMood(state: String?) {
    val mood = moodOf(state)
    if (mood == Mood.None) return
    val seeds = remember(mood) { Random(7).let { r -> List(if (mood == Mood.Rain) 70 else 46) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) } } }
    val base = if (Sh.light) Color(0xFF1A1917) else Color(0xFFFFF7EB)
    // Wolken und Sonne zeichnet der Hintergrund selbst (siehe [weatherBackdrop]) – hier nur die bewegten Arten.
    if (mood == Mood.Clouds || mood == Mood.Sun) return
    // Regen, Schnee, Sterne: kleine Striche/Punkte, mit ~15 Bildern pro Sekunde – nur solange Sofa Hub sichtbar ist.
    val period = if (mood == Mood.Rain) 1_600L else 24_000L
    val t = produceWhileVisible(0f, mood) {
        val start = System.currentTimeMillis()
        while (true) {
            value = ((System.currentTimeMillis() - start) % period) / period.toFloat()
            kotlinx.coroutines.delay(66)
        }
    }
    // Eigene Ebene: jedes Animationsbild zeichnet nur diese Fläche neu, nicht den Bildschirm darunter.
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
                val w = size.width
                val h = size.height
                val tt = t.value
                when (mood) {
                    Mood.Rain -> seeds.forEach { (x, y, s) ->
                        val yy = ((y + tt * (0.8f + s)) % 1f) * h * 1.1f - h * .05f
                        val xx = x * w + yy * .12f
                        drawLine(base.copy(alpha = .05f + .05f * s), Offset(xx, yy), Offset(xx + 8f, yy + 34f + 20f * s), 1.6f, StrokeCap.Round)
                    }
                    Mood.Snow -> seeds.forEach { (x, y, s) ->
                        val yy = ((y + tt * (0.5f + s * .6f)) % 1f) * h
                        val xx = (x * w + sin((tt * 2 * PI + s * 10).toFloat()) * 30f) % w
                        drawCircle(base.copy(alpha = .10f + .08f * s), 2.5f + 3f * s, Offset(xx, yy))
                    }
                    Mood.Stars -> seeds.forEach { (x, y, s) ->
                        val tw = .5f + .5f * sin((tt * 2 * PI * (1 + s * 3) + s * 20).toFloat())
                        drawCircle(base.copy(alpha = .05f + .12f * tw * s), 1.5f + 1.5f * s, Offset(x * w, y * h * .55f))
                    }
                }
    }
}

/**
 * Stille Wetter-Stimmung (Wolken, Sonne) zum Zeichnen in die zwischengespeicherte Hintergrundebene: große Verläufe
 * ohne eigene Ebene – jede zusätzliche bildschirmgroße Ebene kostet den Fire TV bei jedem Bild spürbar Zeit.
 */
fun weatherBackdrop(state: String?, light: Boolean): (DrawScope.() -> Unit)? {
    val mood = moodOf(state)
    if (mood != Mood.Clouds && mood != Mood.Sun) return null
    val base = if (light) Color(0xFF1A1917) else Color(0xFFFFF7EB)
    val seeds = Random(7).let { r -> List(3) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) } }
    return {
        val w = size.width
        val h = size.height
        if (mood == Mood.Sun) {
            val c = Offset(w * .86f, -h * .08f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFC46B).copy(alpha = .16f), Color.Transparent), c, h * .75f), h * .75f, c)
        } else {
            seeds.forEach { (x, y, sd) ->
                val c = Offset((x * 1.2f - .1f) * w, h * (.15f + y * .5f))
                val r = w * (.22f + sd * .12f)
                drawCircle(Brush.radialGradient(listOf(base.copy(alpha = .045f), Color.Transparent), c, r), r, c)
            }
        }
    }
}
