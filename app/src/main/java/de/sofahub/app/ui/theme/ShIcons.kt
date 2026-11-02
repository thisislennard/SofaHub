package de.sofahub.app.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

/** Linien-Icons aus den Design-Artboards (24er-Raster, nur Kontur). rect/circle sind in Pfade umgerechnet. */
enum class ShIcon(vararg val paths: String) {
    Sofa("M5.5 10.5V8.2a3 3 0 0 1 3-3h7a3 3 0 0 1 3 3v2.3", "M3 13.2a2.1 2.1 0 0 1 4.2 0V14h9.6v-.8a2.1 2.1 0 0 1 4.2 0v3.6a1.6 1.6 0 0 1-1.6 1.6H4.6A1.6 1.6 0 0 1 3 16.8z", "M6 18.4v1.6M18 18.4v1.6M12 5.2V14"),
    Cloud("M7 18a4.5 4.5 0 0 1-.5-9 6 6 0 0 1 11.5 1.5A3.75 3.75 0 0 1 17.5 18z"),
    Sun("M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"),
    Rain("M7 15a4.5 4.5 0 0 1-.5-9 6 6 0 0 1 11.5 1.5A3.75 3.75 0 0 1 17.5 15z", "M8 18l-1 3M12 18l-1 3M16 18l-1 3"),
    Sliders("M4 7h10M18 7h2M4 17h4M12 17h8", "M16 5a2 2 0 1 0 0 4a2 2 0 1 0 0-4z", "M10 15a2 2 0 1 0 0 4a2 2 0 1 0 0-4z"),
    Cast("M2 8V6.5A2.5 2.5 0 0 1 4.5 4h15A2.5 2.5 0 0 1 22 6.5v11a2.5 2.5 0 0 1-2.5 2.5H14", "M2 12a8 8 0 0 1 8 8M2 16a4 4 0 0 1 4 4", "M2.5 20h.01"),
    Bulb("M9 18h6M10 21h4", "M12 3a6 6 0 0 0-3.6 10.8c.6.5 1 1.2 1 2V16h5.2v-.2c0-.8.4-1.5 1-2A6 6 0 0 0 12 3z"),
    Plug("M9 2v5M15 2v5", "M6 7h12v4a6 6 0 0 1-12 0z", "M12 17v5"),
    Thermo("M14 14.5V5a2 2 0 0 0-4 0v9.5a4 4 0 1 0 4 0z"),
    Film("M5.5 4h13A2.5 2.5 0 0 1 21 6.5v11a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5v-11A2.5 2.5 0 0 1 5.5 4z", "M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4"),
    Edit("M4 20h4L19 9l-4-4L4 16z"),
    Home("M3 11l9-7 9 7", "M5 10v10h14V10"),
    Window("M6 3h12a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M12 3v18M4 12h16"),
    Devices("M5 5h9a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M18.2 9h1.6a1.2 1.2 0 0 1 1.2 1.2v7.6a1.2 1.2 0 0 1-1.2 1.2h-1.6a1.2 1.2 0 0 1-1.2-1.2v-7.6a1.2 1.2 0 0 1 1.2-1.2z", "M7 19h6"),
    ChevronRight("M9 6l6 6-6 6"),
    ChevronLeft("M15 6l-6 6 6 6"),
    ArrowRight("M5 12h14M13 6l6 6-6 6"),
    Check("M5 12.5l4.5 4.5L19 7.5"),
    Warning("M12 3l9.5 17h-19z", "M12 10v4M12 17.5v.01"),
    Wifi("M2 8.5a15 15 0 0 1 20 0M5 12a10 10 0 0 1 14 0M8.5 15.5a5 5 0 0 1 7 0M12 19h.01"),
    WifiOff("M2 8.5a15 15 0 0 1 20 0M5 12a10 10 0 0 1 14 0M8.5 15.5a5 5 0 0 1 7 0M12 19h.01", "M3 3l18 18"),
    Laptop("M5 4h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M2 20h20"),
    Phone("M8.5 2h7A2.5 2.5 0 0 1 18 4.5v15a2.5 2.5 0 0 1-2.5 2.5h-7A2.5 2.5 0 0 1 6 19.5v-15A2.5 2.5 0 0 1 8.5 2z", "M11 18h2"),
    Download("M12 4v11M7 10l5 5 5-5M5 20h14"),
    Info("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 11v6M12 7.5v.01"),
    Grid("M5 3h3.5a2 2 0 0 1 2 2v3.5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M15.5 3H19a2 2 0 0 1 2 2v3.5a2 2 0 0 1-2 2h-3.5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M5 13.5h3.5a2 2 0 0 1 2 2V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3.5a2 2 0 0 1 2-2z", "M15.5 13.5H19a2 2 0 0 1 2 2V19a2 2 0 0 1-2 2h-3.5a2 2 0 0 1-2-2v-3.5a2 2 0 0 1 2-2z"),
    Trash("M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"),
    Plus("M12 5v14M5 12h14"),
    Image("M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M8.5 8a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z", "M21 15l-5-5L5 20"),
    Note("M6 3h9l5 5v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M14 3v6h6M8 13h8M8 17h5"),
    Palette("M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0-16z", "M12 4v16"),
    Assign("M4 6h10M4 12h16M4 18h7", "M18 4a2 2 0 1 0 0 4a2 2 0 1 0 0-4z", "M15 16a2 2 0 1 0 0 4a2 2 0 1 0 0-4z"),
    Play("M7 4l13 8-13 8z"),
    Pause("M7 4h3v16H7zM14 4h3v16h-3z"),
    Power("M12 3v9", "M6.3 6.3a8 8 0 1 0 11.4 0"),
    VolumeUp("M4 9h4l5-4v14l-5-4H4z", "M16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12"),
    VolumeDown("M4 9h4l5-4v14l-5-4H4z", "M16.5 8.5a5 5 0 0 1 0 7"),
    Qr("M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 18h2v2h-2zM14 18h2v2h-2zM18 14h2v2h-2z"),
    Layout("M4 4h16v7H4z", "M4 15h7v5H4zM15 15h5v5h-5z"),
    ChevronUp("M6 15l6-6 6 6"),
    ChevronDown("M6 9l6 6 6-6"),
    Calendar("M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M3 10h18M8 3v4M16 3v4"),
    History("M3 12a9 9 0 1 0 3-6.7L3 8", "M3 3v5h5M12 7v5l3 3"),
    Link("M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"),
}

private val iconCache = mutableMapOf<ShIcon, ImageVector>()

private fun ShIcon.vector(): ImageVector = iconCache.getOrPut(this) {
    val builder = ImageVector.Builder(name.lowercase(), 24.dp, 24.dp, 24f, 24f)
    paths.forEach { d ->
        builder.addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    builder.build()
}

@Composable
fun Icon(icon: ShIcon, color: Color, size: Int, modifier: Modifier = Modifier) {
    val painter = rememberVectorPainter(remember(icon) { icon.vector() })
    Image(painter, contentDescription = null, colorFilter = ColorFilter.tint(color), modifier = modifier.size(size.dp))
}
