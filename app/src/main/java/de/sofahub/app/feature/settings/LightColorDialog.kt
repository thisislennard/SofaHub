package de.sofahub.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.sofahub.app.core.model.ActionKind
import de.sofahub.app.core.model.HaAction
import de.sofahub.app.feature.dashboard.LightLook
import de.sofahub.app.integration.automation.LightColors
import de.sofahub.app.ui.components.FocusOnShow
import de.sofahub.app.ui.components.ShButton
import de.sofahub.app.ui.components.ShDialog
import de.sofahub.app.ui.components.Tile
import de.sofahub.app.ui.components.Txt
import de.sofahub.app.ui.theme.Icon
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon

fun LightColors.Choice.swatch(): Color =
    rgb?.let { Color(it[0], it[1], it[2]) } ?: LightLook.kelvin((kelvin ?: 2700).toDouble())

/** Farbpunkt einer Aktion (für die Automationsliste), null ohne Farbe. */
fun HaAction.swatch(): Color? = when {
    rgb != null && rgb.size == 3 -> Color(rgb[0], rgb[1], rgb[2])
    kelvin != null -> LightLook.kelvin(kelvin.toDouble())
    else -> null
}

/**
 * Farbe und Helligkeit für ein Licht wählen (beim Einschalten durch eine Automation). Feste Farben statt Farbrad,
 * damit es mit der Fernbedienung schnell geht. „Ohne Farbe“ schaltet nur ein.
 */
@Composable
fun LightColorDialog(title: String, entityId: String, initial: HaAction?, onSave: (HaAction) -> Unit, onClose: () -> Unit) {
    val choices = LightColors.all
    var pick by remember {
        mutableStateOf(choices.indexOfFirst { (initial?.rgb != null && it.rgb == initial.rgb) || (initial?.rgb == null && initial?.kelvin != null && it.kelvin == initial.kelvin) })
    }
    // Farbe außerhalb der festen Auswahl (z. B. aus einer Sicherung): bleibt erhalten, solange keine andere gewählt wird.
    val initialPick = remember { pick }
    var brightness by remember { mutableStateOf(initial?.brightness) }
    ShDialog(title, "Farbe und Helligkeit beim Einschalten", ShIcon.Bulb, onDismiss = onClose, width = 940) {
        FocusOnShow(remember { if (pick >= 0) "lc-$pick" else "lc-0" })
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            choices.chunked(6).forEachIndexed { row, list ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    list.forEachIndexed { col, c ->
                        val i = row * 6 + col
                        val sel = i == pick
                        Tile("lc-$i", Modifier.size(width = 112.dp, height = 96.dp), radius = 20, borderColor = if (sel) Sh.text else null, onClick = { pick = if (sel) -1 else i }) {
                            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(38.dp).clip(CircleShape).background(c.swatch()).border(if (sel) 3.dp else 0.dp, Sh.text, CircleShape), contentAlignment = Alignment.Center) {
                                    if (sel) Icon(ShIcon.Check, Color(0xFF1A1917), 22)
                                }
                                Txt(c.name, Sh.style(16, FontWeight.Medium), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        Txt("Helligkeit", Sh.style(21, color = Sh.text2))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ShButton("lb-keep", "unverändert", height = 54, fontSize = 19, primary = brightness == null) { brightness = null }
            LightColors.brightnessSteps.forEach { b ->
                ShButton("lb-$b", "$b %", height = 54, fontSize = 19, primary = brightness == b) { brightness = b }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShButton("lc-save", "Übernehmen", primary = true) {
                val c = choices.getOrNull(pick)
                val keep = pick == -1 && initialPick == -1
                onSave(
                    HaAction(
                        entityId, ActionKind.ON,
                        rgb = if (keep) initial?.rgb else c?.rgb,
                        kelvin = if (keep) initial?.kelvin else c?.kelvin,
                        brightness = brightness,
                    ),
                )
            }
            ShButton("lc-cancel", "Abbrechen") { onClose() }
        }
    }
}
