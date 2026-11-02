package de.sofahub.app.feature.home

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.sofahub.app.core.model.Availability
import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.feature.dashboard.Bindings
import de.sofahub.app.feature.dashboard.DashboardModel
import de.sofahub.app.feature.dashboard.LightGlow
import de.sofahub.app.feature.dashboard.LightLook
import de.sofahub.app.integration.homeassistant.EntityView
import de.sofahub.app.ui.components.IconChip
import de.sofahub.app.ui.components.Tile
import de.sofahub.app.ui.components.TileStyle
import de.sofahub.app.ui.components.Txt
import de.sofahub.app.ui.theme.Icon
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon
import kotlin.math.ceil
import kotlin.math.sqrt

/** Zusammenfassung eines Raums für Raumkarten und Hausplan. */
data class RoomInfo(
    val name: String,
    val devices: Int,
    val lights: Int,
    val lightsOn: Int,
    val windowsOpen: Int,
    val unavailable: Int,
    val temperature: String?,
    val glow: LightGlow?,
)

object Rooms {
    fun of(bindings: Bindings, ha: HaSnapshot): List<RoomInfo> {
        val ids = (bindings.smartHome + bindings.windows + bindings.roles.values).distinct()
        val byRoom = ids.groupBy { DashboardModel.roomOf(it, bindings, ha) }.filterKeys { it != null }
        // Temperatur: irgendein Temperatursensor im Raum, auch wenn er nicht ausgewählt ist – ein Durchlauf für alle
        // Räume statt einer Suche über alle Entitäten pro Raum.
        val tempByRoom = HashMap<String, HaEntity>()
        if (byRoom.isNotEmpty()) ha.entities.values.forEach { e ->
            if (e.domain != "sensor" || e.attrString("device_class") != "temperature" || e.availability != Availability.NORMAL) return@forEach
            DashboardModel.roomOf(e.entityId, bindings, ha)?.let { tempByRoom.putIfAbsent(it, e) }
        }
        return byRoom.map { (room, roomIds) ->
            val entities = roomIds.mapNotNull { ha.entities[it] }
            val lights = entities.filter { it.domain == "light" }
            val lit = lights.filter { EntityView.isOn(it) }
            val temp = tempByRoom[room]
            RoomInfo(
                name = room!!,
                devices = roomIds.size,
                lights = lights.size,
                lightsOn = lit.size,
                windowsOpen = entities.count { EntityView.isWindowSensor(it) && EntityView.isOn(it) },
                unavailable = entities.count { it.availability == Availability.UNAVAILABLE },
                temperature = temp?.let { EntityView.temperatureText(it) },
                glow = lit.firstNotNullOfOrNull { LightLook.glow(it) },
            )
        }.sortedWith(compareByDescending<RoomInfo> { it.devices }.thenBy { it.name.lowercase() })
    }

    fun summary(r: RoomInfo): String = listOfNotNull(
        if (r.lights > 0) (if (r.lightsOn > 0) "${r.lightsOn} von ${r.lights} Lichtern an" else "Licht aus") else null,
        if (r.windowsOpen > 0) "${r.windowsOpen} Fenster offen" else null,
        if (r.devices == 1) "1 Gerät" else "${r.devices} Geräte",
    ).joinToString(" · ")

    fun icon(name: String): ShIcon {
        val n = name.lowercase()
        return when {
            "wohn" in n -> ShIcon.Sofa
            "küche" in n || "kueche" in n -> ShIcon.Thermo
            "bad" in n -> ShIcon.Rain
            "büro" in n || "arbeit" in n -> ShIcon.Laptop
            "flur" in n || "diele" in n || "eingang" in n -> ShIcon.Home
            else -> ShIcon.Bulb
        }
    }
}

private fun glowBrush(g: LightGlow?): Brush? = g?.let {
    Brush.radialGradient(listOf(it.color.copy(alpha = .12f + .30f * it.brightness), it.color.copy(alpha = .03f)), center = Offset(80f, 60f), radius = 700f)
}

/** Raumkarten: ein großer Überblick pro Raum; OK öffnet die Geräte des Raums. */
@Composable
fun RoomCards(rooms: List<RoomInfo>, modifier: Modifier, onOpen: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        items(rooms.size, key = { rooms[it].name }) { i ->
            val r = rooms[i]
            val glow = glowBrush(r.glow)
            Tile(
                key = "room$i",
                modifier = Modifier.fillMaxWidth().height(230.dp),
                radius = 32,
                background = glow,
                focusedBackground = glow,
                borderColor = r.glow?.color?.copy(alpha = .35f),
                padding = PaddingValues(horizontal = 30.dp, vertical = 26.dp),
                onClick = { onOpen(r.name) },
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        IconChip(Rooms.icon(r.name), r.glow?.let { LightLook.readable(it.color) } ?: Sh.soft, r.glow?.color?.copy(alpha = .26f) ?: Sh.veil(.08f), 64, 34, 20)
                        Spacer(Modifier.weight(1f))
                        r.temperature?.let { Txt(it, Sh.style(40, FontWeight.SemiBold, lineHeight = 1f)) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Txt(r.name, Sh.style(32, Sh.W650))
                        Txt(Rooms.summary(r), Sh.style(21, color = if (r.windowsOpen > 0) Sh.warnText else Sh.text3), maxLines = 1)
                    }
                }
                if (r.unavailable > 0) Box(Modifier.align(Alignment.TopEnd).padding(top = 76.dp)) { Icon(ShIcon.WifiOff, Sh.warnText, 24) }
            }
        }
    }
}

/**
 * Schematischer Hausplan: Räume als Blöcke, Größe nach Geräteanzahl; brennendes Licht färbt den Raum ein, offene Fenster
 * erscheinen als orange Kante. Home Assistant kennt keine Grundrisse – die Anordnung ist daher automatisch.
 */
@Composable
fun HousePlan(rooms: List<RoomInfo>, modifier: Modifier, onOpen: (String) -> Unit) {
    if (rooms.isEmpty()) return
    val perRow = ceil(sqrt(rooms.size.toDouble())).toInt().coerceAtLeast(1)
    val rows = rooms.chunked(perRow)
    Column(
        modifier.background(Sh.veil(.04f), RoundedCornerShape(26.dp)).border(3.dp, Sh.veil(.22f), RoundedCornerShape(26.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        var index = 0
        // Größe nach Geräteanzahl, aber mit Mindestmaß, damit auch kleine Räume lesbar bleiben.
        val avgRow = rooms.sumOf { it.devices }.toFloat() / rows.size
        rows.forEach { row ->
            val rowSum = row.sumOf { it.devices }.toFloat()
            Row(Modifier.fillMaxWidth().weight(maxOf(rowSum, avgRow * .7f, 1f)), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { r ->
                    val i = index++
                    PlanRoom(i, r, Modifier.weight(maxOf(r.devices.toFloat(), rowSum * .3f, 1f)).fillMaxHeight(), onOpen)
                }
            }
        }
    }
}

@Composable
private fun PlanRoom(i: Int, r: RoomInfo, modifier: Modifier, onOpen: (String) -> Unit) {
    val lit = r.glow
    val fill = lit?.let { Brush.verticalGradient(listOf(it.color.copy(alpha = .18f + .30f * it.brightness), it.color.copy(alpha = .08f))) }
    Tile(
        key = "plan$i",
        modifier = modifier,
        radius = 10,
        style = TileStyle.Plain,
        background = fill ?: Brush.linearGradient(List(2) { Sh.veil(.05f) }),
        focusedBackground = fill,
        borderColor = Sh.veil(.18f),
        focusScale = 1.02f,
        padding = PaddingValues(18.dp),
        onClick = { onOpen(r.name) },
    ) {
        if (r.windowsOpen > 0) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth(.5f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Sh.orange))
        PlanLabel(r)
    }
}

@Composable
private fun PlanLabel(r: RoomInfo) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Txt(r.name, Sh.style(24, FontWeight.SemiBold), maxLines = 1)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            r.temperature?.let { Txt(it, Sh.style(26, FontWeight.Medium)) }
            LegendLine(r)
        }
    }
}

@Composable
private fun LegendLine(r: RoomInfo) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (r.lightsOn > 0) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(r.glow?.color ?: Sh.sun))
            Txt("${r.lightsOn} an", Sh.style(19, color = Sh.text2))
        }
        if (r.windowsOpen > 0) {
            Icon(ShIcon.Window, Sh.orangeText, 18)
            Txt("${r.windowsOpen}", Sh.style(19, color = Sh.orangeText))
        }
    }
}

/** Legende unter dem Plan. */
@Composable
fun PlanLegend() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(Sh.sun.copy(alpha = .6f)))
        Txt("Licht an (Farbe der Lampe)", Sh.style(19, color = Sh.hint))
        Box(Modifier.size(22.dp, 6.dp).clip(RoundedCornerShape(3.dp)).background(Sh.orange))
        Txt("Fenster offen", Sh.style(19, color = Sh.hint))
        Txt("Größe = Anzahl Geräte · Anordnung automatisch", Sh.style(19, color = Sh.hint))
    }
}
