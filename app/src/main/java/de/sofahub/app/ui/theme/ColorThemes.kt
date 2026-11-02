package de.sofahub.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Wählbare Farbdesigns. Sie ändern Grundfläche, Ambient-Licht, Akzent (Status „verbunden“, Schalter „An“) und Logo-Verlauf.
 * Bedeutungsfarben (Sonne = Licht, Koralle = Warnung …) bleiben in allen Designs gleich, damit Zustände eindeutig bleiben.
 */
data class ColorTheme(
    val id: String,
    val name: String,
    val bg: Color,
    val surface: Color,
    val accent: Color,
    val accentText: Color,
    val accentFill: Color,
    val logoStart: Color,
    val logoEnd: Color,
    val ambientA: Color,
    val ambientB: Color,
    val ambientC: Color,
    /** Helles Design: dunkle Schrift, weiße Glasflächen, dunkle Überlagerungen. */
    val light: Boolean = false,
)

object ColorThemes {
    val Anthrazit = ColorTheme(
        "anthrazit", "Anthrazit", Color(0xFF181715), Color(0xFF1D1C1F),
        Color(0xFF2DD4BF), Color(0xFF5EEAD4), Color(0xFF0FA896), Color(0xFF2DD4BF), Color(0xFF7C6CF6),
        Color(0x38A78BFA), Color(0x2E2DD4BF), Color(0x21FF7D6B),
    )
    val Mitternacht = ColorTheme(
        "mitternacht", "Mitternacht", Color(0xFF0E131D), Color(0xFF141B27),
        Color(0xFF60A5FA), Color(0xFF93C5FD), Color(0xFF2563EB), Color(0xFF38BDF8), Color(0xFF6366F1),
        Color(0x406366F1), Color(0x3338BDF8), Color(0x2422D3EE),
    )
    val Wald = ColorTheme(
        "wald", "Wald", Color(0xFF111915), Color(0xFF16201B),
        Color(0xFF4ADE80), Color(0xFF86EFAC), Color(0xFF15803D), Color(0xFF4ADE80), Color(0xFF0EA5A4),
        Color(0x3310B981), Color(0x2984CC16), Color(0x2414B8A6),
    )
    val Abendrot = ColorTheme(
        "abendrot", "Abendrot", Color(0xFF1B1413), Color(0xFF221917),
        Color(0xFFFB923C), Color(0xFFFDBA74), Color(0xFFC2410C), Color(0xFFFB923C), Color(0xFFF43F5E),
        Color(0x38F43F5E), Color(0x33FB923C), Color(0x24F59E0B),
    )
    val Lavendel = ColorTheme(
        "lavendel", "Lavendel", Color(0xFF16131E), Color(0xFF1C1826),
        Color(0xFFA78BFA), Color(0xFFC4B5FD), Color(0xFF7C3AED), Color(0xFFA78BFA), Color(0xFFEC4899),
        Color(0x40A78BFA), Color(0x2EEC4899), Color(0x246366F1),
    )

    val Hell = ColorTheme(
        "hell", "Hell", Color(0xFFF1ECE4), Color(0xFFFFFFFF),
        Color(0xFF0D9488), Color(0xFF0F766E), Color(0xFF0D9488), Color(0xFF14B8A6), Color(0xFF6366F1),
        Color(0x30A78BFA), Color(0x2E2DD4BF), Color(0x26FF9F6B), light = true,
    )
    val Oled = ColorTheme(
        "oled", "OLED", Color(0xFF000000), Color(0xFF0A0A0A),
        Color(0xFF2DD4BF), Color(0xFF5EEAD4), Color(0xFF0FA896), Color(0xFF2DD4BF), Color(0xFF7C6CF6),
        Color(0x0FA78BFA), Color(0x0D2DD4BF), Color(0x00000000),
    )

    val all = listOf(Anthrazit, Mitternacht, Wald, Abendrot, Lavendel, Hell, Oled)

    fun byId(id: String?): ColorTheme = all.firstOrNull { it.id == id } ?: Anthrazit
}
