package de.sofahub.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import de.sofahub.app.ui.theme.Sh

enum class Illustration { Apps, Recent, Devices, Search, Printer, Photos, Automation }

/**
 * Kleine Linien-Illustrationen für leere Zustände (Raster 120×120). Linien in Schriftfarbe, ein Akzent in Designfarbe,
 * darunter ein weicher Schein – passt sich hellen und dunklen Designs an.
 */
@Composable
fun EmptyArt(kind: Illustration, size: Int = 120, modifier: Modifier = Modifier) {
    val line = Sh.veil(.42f)
    val accent = Sh.teal
    val warm = Sh.sun
    Canvas(modifier.size(size.dp)) {
        val u = this.size.minDimension / 120f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        val stroke = Stroke(3.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun DrawScope.rr(x: Float, y: Float, w: Float, h: Float, r: Float, color: androidx.compose.ui.graphics.Color = line) =
            drawRoundRect(color, p(x, y), Size(w * u, h * u), CornerRadius(r * u), style = stroke)
        fun DrawScope.ln(x1: Float, y1: Float, x2: Float, y2: Float, color: androidx.compose.ui.graphics.Color = line) =
            drawLine(color, p(x1, y1), p(x2, y2), stroke.width, StrokeCap.Round)
        fun DrawScope.spark(x: Float, y: Float, r: Float, color: androidx.compose.ui.graphics.Color) {
            ln(x - r, y, x + r, y, color)
            ln(x, y - r, x, y + r, color)
        }
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .16f), accent.copy(alpha = 0f)), p(60f, 64f), 56 * u), 56 * u, p(60f, 64f))
        when (kind) {
            Illustration.Apps -> {
                rr(18f, 30f, 84f, 52f, 8f)
                ln(46f, 94f, 74f, 94f)
                ln(60f, 82f, 60f, 94f)
                rr(30f, 42f, 24f, 16f, 4f, accent)
                rr(62f, 42f, 28f, 16f, 4f)
                spark(98f, 22f, 6f, warm)
            }
            Illustration.Recent -> {
                drawArc(line, -60f, 300f, false, p(26f, 28f), Size(68 * u, 68 * u), style = stroke)
                ln(60f, 44f, 60f, 62f, accent)
                ln(60f, 62f, 72f, 70f, accent)
                ln(26f, 40f, 30f, 52f)
                ln(30f, 52f, 42f, 48f)
            }
            Illustration.Devices -> {
                val house = Path().apply {
                    moveTo(20 * u, 58 * u); lineTo(60 * u, 24 * u); lineTo(100 * u, 58 * u)
                    moveTo(30 * u, 50 * u); lineTo(30 * u, 96 * u); lineTo(90 * u, 96 * u); lineTo(90 * u, 50 * u)
                }
                drawPath(house, line, style = stroke)
                drawCircle(warm.copy(alpha = .9f), 9 * u, p(60f, 66f), style = stroke)
                ln(56f, 78f, 64f, 78f, warm)
                spark(60f, 50f, 3f, warm)
            }
            Illustration.Search -> {
                drawCircle(line, 22 * u, p(52f, 54f), style = stroke)
                ln(68f, 70f, 92f, 94f)
                ln(44f, 54f, 60f, 54f, accent)
            }
            Illustration.Printer -> {
                rr(24f, 24f, 72f, 72f, 8f)
                ln(24f, 40f, 96f, 40f)
                rr(52f, 40f, 16f, 12f, 3f, accent)
                ln(60f, 52f, 60f, 60f, accent)
                rr(42f, 76f, 36f, 10f, 3f)
                ln(46f, 70f, 74f, 70f, line.copy(alpha = line.alpha * .6f))
            }
            Illustration.Photos -> {
                rr(30f, 22f, 64f, 52f, 6f, line.copy(alpha = line.alpha * .6f))
                rr(22f, 34f, 70f, 56f, 6f)
                val hill = Path().apply { moveTo(28 * u, 82 * u); lineTo(48 * u, 62 * u); lineTo(62 * u, 74 * u); lineTo(72 * u, 66 * u); lineTo(86 * u, 82 * u) }
                drawPath(hill, accent, style = stroke)
                drawCircle(warm, 6 * u, p(74f, 48f), style = stroke)
            }
            Illustration.Automation -> {
                drawArc(line, -50f, 280f, false, p(28f, 30f), Size(64 * u, 64 * u), style = stroke)
                ln(60f, 24f, 60f, 56f, accent)
                spark(94f, 26f, 6f, warm)
                spark(22f, 92f, 4f, warm)
            }
        }
    }
}
