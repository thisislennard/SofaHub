package de.sofahub.app.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.core.model.HomeTile
import de.sofahub.app.feature.MainViewModel
import de.sofahub.app.feature.PickerTarget
import de.sofahub.app.feature.Route
import de.sofahub.app.integration.homeassistant.EntityView
import de.sofahub.app.ui.components.FocusOnShow
import de.sofahub.app.ui.components.ShButton
import de.sofahub.app.ui.components.ShDialog
import de.sofahub.app.ui.components.ShTextField
import de.sofahub.app.ui.components.Tile
import de.sofahub.app.ui.components.Txt
import de.sofahub.app.ui.theme.Icon
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon

/** Seitenrand der Startseite (links/rechts). */
val PAGE_MARGIN = 96.dp

/** Domains, die als Kachel auf der Startseite stehen können. */
val TILE_DOMAINS = setOf(
    "light", "switch", "input_boolean", "fan", "climate", "sensor", "binary_sensor", "media_player", "scene", "script",
    "cover", "lock", "vacuum",
)

/** Angezeigter Name einer Kachel: eigener Name, sonst Name aus Home Assistant. */
fun HomeTile.title(ha: HaSnapshot): String = name?.trim()?.takeIf { it.isNotEmpty() } ?: ha.entities[entityId]?.friendlyName ?: entityId

/** Kachel-Art nach Domain: schaltbar, startbar (Szene/Skript) oder Anzeige (Sensor, Heizung, Mediaplayer …). */
enum class TileKind { SWITCH, SCENE, VALUE }

fun tileKind(entityId: String): TileKind = when (entityId.substringBefore('.')) {
    "light", "switch", "input_boolean", "fan" -> TileKind.SWITCH
    "scene", "script" -> TileKind.SCENE
    else -> TileKind.VALUE
}

/** Knopfleiste mit Szenen und Skripten unter den Kacheln. */
@Composable
fun SceneBar(vm: MainViewModel, ids: List<String>, ha: HaSnapshot) {
    val scenes = ids.mapNotNull { ha.entities[it] }
    if (scenes.isEmpty()) return
    LazyRow(Modifier.bleed(PAGE_MARGIN), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = PAGE_MARGIN, vertical = 8.dp)) {
        items(scenes.size, key = { scenes[it].entityId }) { i ->
            val e = scenes[i]
            Tile(
                key = "scene$i",
                modifier = Modifier.height(64.dp),
                radius = 32,
                padding = PaddingValues(horizontal = 26.dp),
                onClick = { vm.startScene(e.entityId, e.friendlyName) },
            ) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(ShIcon.Film, Sh.violetText, 24)
                    Txt(e.friendlyName, Sh.style(23, FontWeight.SemiBold), maxLines = 1)
                }
            }
        }
    }
}

/**
 * Schnellmenü einer Kachel (OK lang): Helligkeit, Umbenennen, Gerät wechseln, von der Startseite entfernen.
 * Wird auf Bildschirmebene gezeichnet – innerhalb einer Karte würde der Dialog zusammengedrückt.
 */
@Composable
fun TileMenuDialog(vm: MainViewModel, ha: HaSnapshot, index: Int, tile: HomeTile, onRename: () -> Unit, onClose: () -> Unit) {
    val e: HaEntity? = ha.entities[tile.entityId]
    val demo = vm.c.ha.isDemo
    val title = tile.title(ha)
    ShDialog(title, listOfNotNull(e?.let { EntityView.stateLabel(it) }, tile.entityId).joinToString(" · "), ShIcon.Sliders, onDismiss = onClose) {
        val isLight = e?.domain == "light" && EntityView.isOn(e)
        FocusOnShow(if (isLight) "tm-up" else "tm-rename")
        if (isLight) {
            val pct = EntityView.brightnessPercent(e) ?: 100
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ShButton("tm-down", "Dunkler", icon = ShIcon.ChevronLeft) { vm.setBrightness(e.entityId, (pct - 10).coerceAtLeast(5)) }
                Txt("$pct %", Sh.style(30, FontWeight.SemiBold), Modifier.width(110.dp))
                ShButton("tm-up", "Heller", trailingIcon = ShIcon.ChevronRight) { vm.setBrightness(e.entityId, (pct + 10).coerceAtMost(100)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShButton("tm-rename", "Umbenennen", icon = ShIcon.Edit) { if (demo) vm.toast("Im Demo-Modus nicht möglich", warn = true) else { onClose(); onRename() } }
            ShButton("tm-change", "Gerät", icon = ShIcon.Assign) {
                if (demo) vm.toast("Im Demo-Modus nicht möglich", warn = true) else { onClose(); vm.navigate(Route.Picker(PickerTarget.ForTile(index))) }
            }
            ShButton("tm-remove", "Entfernen", icon = ShIcon.Trash, color = Sh.dangerText) {
                if (demo) vm.toast("Im Demo-Modus nicht möglich", warn = true)
                else {
                    vm.editHomeTiles { it.removeAt(index) }
                    vm.toast("Kachel entfernt", "Wieder hinzufügen: Einstellungen › Startseite")
                    onClose()
                }
            }
        }
        ShButton("tm-smarthome", "Smart Home öffnen", icon = ShIcon.Home) { onClose(); vm.navigate(Route.SmartHome()) }
    }
}

/** Namen für eine Kachel eingeben; „Standard“ nimmt wieder den Namen aus Home Assistant. */
@Composable
fun TileNameDialog(vm: MainViewModel, ha: HaSnapshot, index: Int, tile: HomeTile, onClose: () -> Unit) {
    var input by remember { mutableStateOf(tile.title(ha)) }
    val haName = ha.entities[tile.entityId]?.friendlyName
    fun save(name: String?) {
        val clean = name?.trim()?.take(AppSettings.MAX_TILE_NAME)?.takeIf { it.isNotEmpty() && it != haName }
        vm.editHomeTiles { list -> if (index in list.indices) list[index] = list[index].copy(name = clean) }
        vm.toast("Kachel umbenannt", clean ?: haName ?: tile.entityId)
        onClose()
    }
    ShDialog("Kachel umbenennen", "Name auf der Startseite. „Standard“ übernimmt den Namen aus Home Assistant${haName?.let { " („$it“)" } ?: ""}.", ShIcon.Edit, onDismiss = onClose) {
        FocusOnShow("tn-input")
        ShTextField("tn-input", "Name", input, { input = it.take(AppSettings.MAX_TILE_NAME) }, placeholder = haName ?: "", height = 68, mono = false)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShButton("tn-save", "Speichern", primary = true) { save(input) }
            ShButton("tn-default", "Standard") { save(null) }
            ShButton("tn-cancel", "Abbrechen") { onClose() }
        }
    }
}

/** Kleiner runder Symbolknopf (z. B. Hoch/Runter in Listen). */
@Composable
fun IconTileButton(key: String, icon: ShIcon, enabled: Boolean = true, onClick: () -> Unit) {
    Tile(key, Modifier.width(60.dp).height(60.dp), radius = 30, enabled = enabled, onClick = onClick) {
        Box(Modifier.align(Alignment.Center).padding(2.dp)) { Icon(icon, if (enabled) Sh.bright else Sh.muted, 26) }
    }
}

/**
 * Lässt eine waagrechte Liste bis an den Bildschirmrand laufen (über den Seitenrand [h] hinaus). Zusammen mit
 * `contentPadding = h` stehen die Einträge wie gewohnt, aber Fokusrahmen und weiterblätternde Einträge werden nicht am
 * Seitenrand abgeschnitten.
 */
fun Modifier.bleed(h: androidx.compose.ui.unit.Dp): Modifier = this.then(
    androidx.compose.ui.Modifier.layout { measurable, c ->
        val px = h.roundToPx()
        val p = measurable.measure(c.copy(minWidth = c.minWidth + 2 * px, maxWidth = c.maxWidth + 2 * px))
        layout(c.maxWidth, p.height) { p.place(-px, 0) }
    },
)
