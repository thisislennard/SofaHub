package de.sofahub.app.feature.dashboard

import androidx.compose.ui.draw.clipToBounds
import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.effectiveHomeTiles
import de.sofahub.app.core.model.HomeBlock
import de.sofahub.app.core.model.HomeTile
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sofahub.app.core.model.ActionStatus
import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.FavoriteApp
import de.sofahub.app.core.model.Availability
import de.sofahub.app.core.model.HaConnection
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.core.model.Role
import de.sofahub.app.feature.MainViewModel
import de.sofahub.app.feature.PickerTarget
import de.sofahub.app.feature.Route
import de.sofahub.app.integration.homeassistant.EntityView
import de.sofahub.app.integration.miracast.MiracastCapability
import de.sofahub.app.ui.components.Dot
import de.sofahub.app.ui.components.FocusOnEnter
import de.sofahub.app.ui.components.Footer
import de.sofahub.app.ui.components.produceWhileVisible
import de.sofahub.app.ui.components.IconChip
import de.sofahub.app.ui.components.ShButton
import de.sofahub.app.ui.components.Skeleton
import de.sofahub.app.ui.components.Spinner
import de.sofahub.app.ui.components.SwitchPill
import de.sofahub.app.ui.components.Tile
import de.sofahub.app.ui.components.TileStyle
import de.sofahub.app.ui.components.Txt
import de.sofahub.app.ui.components.glass
import de.sofahub.app.ui.theme.Icon
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.ZonedDateTime

// Unveränderlich und threadsicher – einmal anlegen statt bei jedem Neuaufbau der Kopfzeile.
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)
private val dateFormat = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMANY)
private val shortDateFormat = DateTimeFormatter.ofPattern("d. MMM", Locale.GERMANY)

private fun local(epochMs: Long) = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())

/** Aktuelle Zeit, minütlich aktualisiert (überall wird nur HH:mm angezeigt – sekündlich wäre unnötige Arbeit). */
@Composable
fun rememberNow(): Long {
    val now by produceWhileVisible(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000 - value % 60_000 + 20)
        }
    }
    return now
}

fun formatTime(epochMs: Long): String = timeFormat.format(local(epochMs))

@Composable
fun HomeScreen(vm: MainViewModel, settings: AppSettings, ha: HaSnapshot) {
    val bindings = remember(settings, vm.c.ha.isDemo) { Bindings.of(settings, vm.c.ha.isDemo) }
    var showRecent by rememberSaveable { mutableStateOf(false) }
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    var renameIndex by remember { mutableStateOf<Int?>(null) }
    var wifiOpen by remember { mutableStateOf(false) }
    val installed by vm.apps.collectAsStateWithLifecycle()
    val layout = settings.homeLayout
    // Auf der Startseite erscheint nur, was eingerichtet ist: ohne Home Assistant keine Smart-Home-Kacheln,
    // Favoriten/Zuletzt genutzt erst, wenn es etwas zu zeigen gibt; Bereiche lassen sich in den Einstellungen ausblenden.
    val haSetUp = vm.c.ha.isDemo || settings.ha != null
    val homeTiles = if (haSetUp) settings.effectiveHomeTiles(bindings.roles, vm.c.ha.isDemo) else emptyList()
    // Während eines 3D-Drucks steht die Druckerkamera als erste Kachel vorn.
    val printing = remember(ha.entities, vm.c.ha.isDemo) { if (vm.c.ha.isDemo) emptyList() else Printers.active(ha) }
    val tiles = buildList<@Composable RowScope.() -> Unit> {
        if (printing.isNotEmpty()) add { PrinterTile(vm, printing) }
        // Jede Kachel bekommt nur ihr eigenes Gerät: Ändert sich anderswo im Haus ein Sensor, bleibt sie unberührt.
        homeTiles.forEachIndexed { i, t ->
            val state = DashboardModel.entityTile(t.entityId, ha)
            val title = t.title(ha)
            add { HomeEntityTile(vm, i, t, state, title) { menuIndex = i } }
        }
        // Gäste-WLAN braucht kein Home Assistant – erscheint, sobald es eingetragen ist.
        settings.guestWifi?.let { w -> add { GuestWifiTile(w) { wifiOpen = true } } }
    }
    val hasFavorites = settings.favorites.isNotEmpty()
    val hasRecent = settings.recentApps.any { pkg -> installed.any { it.packageName == pkg } }
    val recentShown = if (!hasFavorites) true else if (!hasRecent) false else showRecent
    val showMirror = layout.showMirror
    FocusOnEnter(if (showMirror) "mirror" else "tile0", "tile0", "app0", "settings")
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(top = 46.dp, bottom = 40.dp)) {
        Box(Modifier.padding(horizontal = 96.dp)) { HomeHeader(vm, settings, ha, bindings) }
        // Inhalt scrollt, falls viele Kacheln und Bereiche nicht auf einen Bildschirm passen; der Fokus zieht mit.
        // Seitenränder innerhalb des Scrollbereichs, damit Fokusrahmen am Rand nicht abgeschnitten werden.
        Column(Modifier.fillMaxWidth().weight(1f).clipToBounds().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp)) {
        Spacer(Modifier.height(28.dp))
        if (showMirror || tiles.isNotEmpty()) {
            val columns = if (showMirror) 2 else 4
            val topCount = if (showMirror) 4 else 8
            val top = tiles.take(topCount)
            // Mit Spiegeln-Kachel: 2×2 daneben (360 hoch). Ohne: Reihen zu je vier Kacheln in normaler Kachelhöhe.
            val rows = if (showMirror) 2 else (top.size + 3) / 4
            val topHeight = if (showMirror) 360.dp else (166 * rows + 28 * (rows - 1)).dp
            Row(Modifier.fillMaxWidth().height(topHeight), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                if (showMirror) MirrorTile(vm, settings, if (top.isEmpty()) Modifier.fillMaxWidth().fillMaxHeight() else Modifier.width(860.dp).fillMaxHeight())
                if (top.isNotEmpty()) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    top.chunked(columns).forEach { row ->
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            row.forEach { it() }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            // Weitere Kacheln in eigenen Reihen darunter.
            tiles.drop(topCount).chunked(4).forEach { row ->
                Spacer(Modifier.height(28.dp))
                Row(Modifier.fillMaxWidth().height(166.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    row.forEach { it() }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        layout.blocks.filter { layout.shows(it) }.forEach { block ->
            when (block) {
                HomeBlock.SCENES -> if (haSetUp && settings.sceneBar.any { it in ha.entities }) {
                    Spacer(Modifier.height(20.dp))
                    SceneBar(vm, settings.sceneBar, ha)
                }
                HomeBlock.APPS -> if (hasFavorites || hasRecent) {
                    Spacer(Modifier.height(28.dp))
                    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Umschalten erst mit OK: beim Durchnavigieren nach unten sollen die Favoriten nicht verschwinden.
                        if (hasFavorites) AppsTab("tab-fav", "Favoriten", ShIcon.Grid, !recentShown) { showRecent = false }
                        if (hasFavorites && hasRecent) Spacer(Modifier.width(12.dp))
                        if (hasRecent) AppsTab("tab-recent", "Zuletzt genutzt", ShIcon.History, recentShown) { showRecent = true }
                        Spacer(Modifier.weight(1f))
                        if (hasFavorites) Tile("edit", Modifier.height(44.dp), radius = 22, padding = PaddingValues(horizontal = 20.dp), onClick = { vm.navigate(Route.Favorites) }) {
                            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(ShIcon.Edit, Sh.soft, 20)
                                Txt("Bearbeiten", Sh.style(20, FontWeight.Medium, Sh.soft))
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (recentShown) RecentAppsRow(vm, settings) else AppsRow(vm, settings)
                }
                HomeBlock.HOUSE -> if (haSetUp) {
                    Spacer(Modifier.height(30.dp))
                    HouseBar(vm, bindings, ha)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.padding(horizontal = 96.dp)) { Footer(
            keys = listOf("◀ ▶ ▲ ▼" to "Navigieren", "OK" to "Auswählen", "OK lang" to "Kachel-Menü", "↩" to "Zurück"),
            badge = if (vm.c.ha.isDemo) "Demo · Beispieldaten" else null,
        ) {
            val agenda = rememberAgenda(vm, settings, ha)
            if (settings.note.isNotBlank() || agenda.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(720.dp)) {
                    Spacer(Modifier.weight(1f))
                    if (agenda.isNotEmpty()) {
                        Icon(ShIcon.Calendar, Sh.hint, 22)
                        Txt(agenda.joinToString(" · "), Sh.style(21, color = Sh.text2), Modifier.weight(1f, fill = false))
                    }
                    if (settings.note.isNotBlank()) {
                        if (agenda.isNotEmpty()) Spacer(Modifier.width(14.dp))
                        Icon(ShIcon.Note, Sh.hint, 22)
                        Txt(settings.note.replace('\n', ' '), Sh.style(21, color = Sh.text2), Modifier.weight(1f, fill = false))
                    }
                }
            }
        } }
    }
    menuIndex?.let { i -> homeTiles.getOrNull(i)?.let { t -> TileMenuDialog(vm, ha, i, t, onRename = { renameIndex = i }) { menuIndex = null } } }
    renameIndex?.let { i -> homeTiles.getOrNull(i)?.let { t -> TileNameDialog(vm, ha, i, t) { renameIndex = null } } }
    if (wifiOpen) settings.guestWifi?.let { w -> GuestWifiDialog(w) { wifiOpen = false } }
    }
}

/** Kachel für eine frei gewählte HA-Entität – Darstellung nach Domain. */
@Composable
private fun RowScope.HomeEntityTile(vm: MainViewModel, index: Int, tile: HomeTile, state: RoleTile, title: String, onMenu: () -> Unit) {
    val key = "tile$index"
    when (tileKind(tile.entityId)) {
        TileKind.SWITCH -> {
            val (icon, fg, bg) = when (tile.entityId.substringBefore('.')) {
                "light" -> Triple(ShIcon.Bulb, Sh.sun, Color(0x3DFFC94D))
                "fan" -> Triple(ShIcon.Devices, Sh.skyText, Sh.chip(Sh.sky))
                "input_boolean" -> Triple(ShIcon.Power, Sh.tealText, Sh.chip(Sh.teal))
                else -> Triple(ShIcon.Plug, Sh.coralSoft, Color(0x3DFF7D6B))
            }
            SwitchRoleTile(vm, key, tile.entityId, title, icon, fg, bg, state, onMenu)
        }
        TileKind.SCENE -> SceneTile(vm, key, tile.entityId, title, state, onMenu)
        TileKind.VALUE -> TemperatureTile(vm, key, tile.entityId, title, state, onMenu)
    }
}

/** Termine der gewählten Kalender (heute/morgen), alle 10 Minuten neu geladen. */
@Composable
private fun rememberAgenda(vm: MainViewModel, settings: AppSettings, ha: HaSnapshot): List<String> {
    val ids = settings.calendarEntities
    val connected = ha.connection == HaConnection.Connected && !vm.c.ha.isDemo
    val lines by produceWhileVisible(emptyList<String>(), ids, connected) {
        if (ids.isEmpty() || !connected) {
            value = emptyList()
            return@produceWhileVisible
        }
        while (true) {
            val now = ZonedDateTime.now()
            val (start, end) = Agenda.window(now)
            value = Agenda.lines(vm.c.ha.calendarEvents(ids, start, end), now)
            delay(10 * 60_000L)
        }
    }
    return lines
}

@Composable
private fun HomeHeader(vm: MainViewModel, settings: AppSettings, ha: HaSnapshot, bindings: Bindings) {
    val now = rememberNow()
    Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Sh.logo), contentAlignment = Alignment.Center) {
            Icon(ShIcon.Sofa, Color.White, 30)
        }
        Spacer(Modifier.width(16.dp))
        val hour = remember(now / 60_000) { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
        Txt(if (settings.greeting) de.sofahub.app.ui.components.greeting(hour) else "Sofa Hub", Sh.style(34, Sh.W650, letterSpacing = (-0.03).em))
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            DashboardModel.weather(bindings, ha)?.let { (temp, condition) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(weatherIcon(ha.entities[bindings.roles[Role.WEATHER]]?.state), Sh.soft, 34)
                    Txt(temp, Sh.style(30, FontWeight.Medium))
                    Txt(condition, Sh.style(24, color = Sh.text3))
                }
                Box(Modifier.width(1.dp).height(40.dp).background(Sh.veil(0.141f)))
            }
            Column(horizontalAlignment = Alignment.End) {
                Txt(formatTime(now), Sh.style(40, FontWeight.SemiBold, letterSpacing = (-0.03).em, lineHeight = 1f))
                Spacer(Modifier.height(4.dp))
                Txt(dateFormat.format(local(now)), Sh.style(20, color = Sh.text3))
            }
            if (settings.photos.isNotEmpty()) {
                Tile("photos", Modifier.size(64.dp), radius = 32, onClick = { vm.navigate(Route.Slideshow) }) {
                    Box(Modifier.align(Alignment.Center)) { Icon(ShIcon.Image, Sh.bright, 30) }
                }
            }
            val update by vm.updates.collectAsStateWithLifecycle()
            val updateReady = update is de.sofahub.app.integration.updates.UpdateState.Available
            Tile("settings", Modifier.size(64.dp), radius = 32, onClick = { vm.navigate(if (updateReady) Route.Updates else Route.Settings()) }) {
                Box(Modifier.align(Alignment.Center)) { Icon(ShIcon.Sliders, Sh.bright, 30) }
                // Neues Update gefunden (tägliche Prüfung): Punkt am Knopf, OK führt direkt zur Update-Seite.
                if (updateReady) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp).clip(CircleShape).background(Sh.teal))
            }
        }
    }
}

fun weatherIcon(state: String?): ShIcon = when (state) {
    "sunny", "clear-night" -> ShIcon.Sun
    "rainy", "pouring", "lightning-rainy", "snowy-rainy", "hail" -> ShIcon.Rain
    else -> ShIcon.Cloud
}

@Composable
private fun MirrorTile(vm: MainViewModel, settings: AppSettings, modifier: Modifier) {
    val capability by vm.miracast.collectAsStateWithLifecycle()
    val name = vm.deviceName
    Tile(
        key = "mirror",
        modifier = modifier,
        radius = 36,
        background = Brush.linearGradient(listOf(Color(0x572DD4BF), Color(0x4260A5FA), Color(0x4DA78BFA))),
        borderColor = Color(0x24FFFFFF),
        padding = PaddingValues(horizontal = 48.dp, vertical = 44.dp),
        onClick = { vm.startMirroring() },
        onLongClick = { vm.navigate(Route.Mirror()) },
    ) { focused ->
        MirrorRings(focused, Modifier.align(Alignment.TopEnd).offset(x = 158.dp, y = (-74).dp))
        Box(
            Modifier.align(Alignment.CenterEnd).size(104.dp).clip(RoundedCornerShape(30.dp))
                .background(Brush.linearGradient(listOf(Color(0x592DD4BF), Color(0x1F2F9AA2))))
                .border(1.dp, Color(0x59FFFFFF), RoundedCornerShape(30.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(ShIcon.Cast, Color(0xFFE6FBFB), 56) }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Txt("MIRACAST", Sh.style(22, FontWeight.SemiBold, Sh.tealText, letterSpacing = .08.em))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt("Bildschirm\nspiegeln", Sh.style(60, Sh.W650, letterSpacing = (-0.035).em, lineHeight = 1.02f), maxLines = 2)
                Txt("Laptop oder Handy verbinden", Sh.style(28, color = Sh.text2))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (settings.lastMirrorEpochMs > 0) Txt(lastMirrorText(settings), Sh.style(20, color = Sh.text3))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                when (capability) {
                    MiracastCapability.SUPPORTED -> {
                        Dot(Sh.teal)
                        Txt(name?.let { "Bereit als „$it“" } ?: "Bereit zum Spiegeln", Sh.style(22, color = Sh.soft))
                    }
                    else -> {
                        Dot(Sh.warnText)
                        Txt("Direktstart nicht verfügbar · OK zeigt den Ausweichweg", Sh.style(22, color = Sh.soft))
                    }
                }
            }
            }
        }
    }
}

/** „Zuletzt gespiegelt: heute 20:15 · 45 min“ */
private fun lastMirrorText(s: AppSettings): String {
    val then = local(s.lastMirrorEpochMs).toLocalDate()
    val today = java.time.LocalDate.now()
    val day = when (then) {
        today -> "heute"
        today.minusDays(1) -> "gestern"
        else -> shortDateFormat.format(then)
    }
    val duration = if (s.lastMirrorMinutes > 0) " · ${s.lastMirrorMinutes} min" else ""
    return "Zuletzt gespiegelt: $day ${formatTime(s.lastMirrorEpochMs)}$duration"
}

/** Puls-Ringe laufen nur, solange die Kachel fokussiert ist. */
@Composable
private fun MirrorRings(focused: Boolean, modifier: Modifier) {
    Box(modifier.size(420.dp)) {
        val sizes = listOf(420 to 0, 300 to 500, 180 to 1000)
        sizes.forEach { (s, delayMs) ->
            // Konzentrisch wie im Entwurf (420/300/180 px).
            val offset = (420 - s) / 2
            val ringMod = Modifier.offset(x = offset.dp, y = offset.dp).size(s.dp)
            if (focused) {
                // Drei Pulse nach dem Fokussieren, danach ruhig – Dauer-Animation hält den Fire TV sonst ständig beschäftigt.
                val p = remember { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(Unit) {
                    delay(delayMs.toLong())
                    repeat(3) { p.snapTo(0f); p.animateTo(1f, tween(2400)) }
                    p.snapTo(.35f)
                }
                Box(
                    ringMod.graphicsLayer {
                        val v = p.value
                        val sc = .92f + .2f * v
                        scaleX = sc; scaleY = sc
                        alpha = if (v < .35f) v / .35f else 1f - (v - .35f) / .65f
                    }.border(1.5.dp, Color(0x47FFFFFF), CircleShape),
                )
            } else {
                Box(ringMod.border(1.5.dp, Color(0x47FFFFFF), CircleShape))
            }
        }
    }
}

@Composable
private fun RowScope.SwitchRoleTile(
    vm: MainViewModel, key: String, entityId: String, title: String, icon: ShIcon, onFg: Color, onBg: Color, tile: RoleTile, onMenu: () -> Unit,
) {
    val off = Sh.veil(0.078f) to Sh.muted
    val ready = tile as? RoleTile.Ready
    val unavailable = ready?.entity?.availability == Availability.UNAVAILABLE || tile is RoleTile.Missing
    val paused = tile is RoleTile.Paused
    // Eingeschaltetes Licht: Kachel leuchtet in der Lichtfarbe, so hell wie die Lampe.
    val glow = LightLook.glow(ready?.entity?.takeIf { !unavailable })
    val glowBrush = glow?.let { g ->
        Brush.radialGradient(
            listOf(g.color.copy(alpha = .14f + .34f * g.brightness), g.color.copy(alpha = .04f + .08f * g.brightness)),
            center = Offset(60f, 40f), radius = 520f,
        )
    }
    Tile(
        key = key,
        modifier = Modifier.weight(1f).fillMaxHeight().alpha(if (paused) .55f else 1f),
        style = if (unavailable) TileStyle.Dashed else TileStyle.Glass,
        background = glowBrush,
        focusedBackground = glowBrush,
        borderColor = glow?.color?.copy(alpha = .35f),
        padding = PaddingValues(horizontal = 28.dp, vertical = 22.dp),
        onClick = {
            when (tile) {
                is RoleTile.Missing -> onMenu()
                is RoleTile.Paused -> vm.toast("Home Assistant nicht verbunden", "Kachel pausiert", warn = true)
                is RoleTile.Ready -> when {
                    unavailable -> vm.toast("$title nicht erreichbar", "Gerät antwortet nicht", warn = true)
                    tile.action == ActionStatus.Pending -> Unit
                    else -> vm.toggle(tile.entity.entityId, EntityView.isOn(tile.entity), title)
                }
            }
        },
        onLongClick = onMenu,
    ) {
        val on = ready != null && EntityView.isOn(ready.entity) && !unavailable
        val pending = ready?.action == ActionStatus.Pending
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                IconChip(
                    icon,
                    glow?.color?.let { LightLook.readable(it) } ?: if (on) onFg else if (unavailable) Sh.muted else off.second,
                    glow?.color?.copy(alpha = .28f) ?: if (on) onBg else off.first,
                )
                when {
                    pending -> Spinner()
                    unavailable -> Icon(ShIcon.Warning, Sh.warnText, 30)
                    ready != null -> SwitchPill(on)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(title, Sh.style(28, FontWeight.SemiBold), maxLines = 1)
                val (label, color) = when (tile) {
                    is RoleTile.Paused -> "— nicht verfügbar" to Sh.text3
                    is RoleTile.Missing -> "Gerät fehlt in Home Assistant" to Sh.coralText
                    is RoleTile.Ready -> when {
                        pending -> "Schaltet …" to Sh.tealText
                        tile.action is ActionStatus.Failed -> "Fehlgeschlagen" to Sh.coralText
                        else -> EntityView.stateLabel(tile.entity) to if (unavailable) Sh.coralText else Sh.text3
                    }
                }
                Txt(label, Sh.style(22, color = color))
            }
        }
    }
}

@Composable
private fun RowScope.TemperatureTile(vm: MainViewModel, key: String, entityId: String, title: String, tile: RoleTile, onMenu: () -> Unit) {
    Tile(
        key = key,
        modifier = Modifier.weight(1f).fillMaxHeight().alpha(if (tile is RoleTile.Paused) .55f else 1f),
        style = if (tile is RoleTile.Missing) TileStyle.Dashed else TileStyle.Glass,
        padding = PaddingValues(horizontal = 28.dp, vertical = 22.dp),
        onClick = {
            if (tile is RoleTile.Missing) onMenu()
            else vm.navigate(Route.SmartHome())
        },
        onLongClick = onMenu,
    ) {
        val entity = (tile as? RoleTile.Ready)?.entity
        val temperature = entity != null && (EntityView.isTemperature(entity) || entity.domain == "climate")
        // Kurve nur für Zahlenwerte (Temperatur, Verbrauch …).
        val numeric = entity != null && entity.domain == "sensor" && entity.state.toDoubleOrNull() != null || temperature
        // Verlauf der letzten 24 Stunden als dezente Kurve hinter dem Inhalt (alle 15 Minuten neu, nur sichtbar).
        val history by produceWhileVisible(emptyList<Pair<Long, Float>>(), entity?.entityId, numeric) {
            val id = entity?.entityId?.takeIf { numeric } ?: return@produceWhileVisible
            while (true) {
                value = vm.c.ha.history(id, 24)
                delay(15 * 60_000L)
            }
        }
        Sparkline(history, Sh.orange, Modifier.fillMaxSize().padding(top = 70.dp))
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                if (temperature || entity == null) IconChip(ShIcon.Thermo, Sh.orangeText, Sh.chip(Sh.orange))
                else IconChip(valueIcon(entity), Sh.skyText, Sh.chip(Sh.sky))
                val value = entity?.let { if (temperature) EntityView.temperatureText(it) else EntityView.stateLabel(it) } ?: "—"
                Txt(value, Sh.style(if (value.length > 8) 30 else 44, FontWeight.SemiBold, letterSpacing = (-0.03).em, lineHeight = 1f), maxLines = 1)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(title, Sh.style(28, FontWeight.SemiBold), maxLines = 1)
                val sub = when (tile) {
                    is RoleTile.Paused -> "— nicht verfügbar"
                    is RoleTile.Missing -> "Gerät fehlt in Home Assistant"
                    is RoleTile.Ready -> (EntityView.climateDetail(tile.entity)?.let { "Heizung · $it" } ?: tile.entity.friendlyName.takeIf { it != title } ?: tile.entity.entityId) +
                        if (tile.stale) " · veraltet" else ""
                }
                Txt(sub, Sh.style(22, color = Sh.text3), maxLines = 1)
            }
        }
    }
}

private fun valueIcon(e: HaEntity): ShIcon = when (e.domain) {
    "media_player" -> ShIcon.Play
    "binary_sensor" -> if (EntityView.isWindowSensor(e)) ShIcon.Window else ShIcon.Info
    "cover" -> ShIcon.Window
    "climate" -> ShIcon.Thermo
    else -> ShIcon.Info
}

/** Kleine Flächenkurve (Werte über Zeit), skaliert auf den eigenen Bereich. */
@Composable
private fun Sparkline(points: List<Pair<Long, Float>>, color: Color, modifier: Modifier) {
    if (points.size < 2) return
    Spacer(modifier.drawWithCache {
        val t0 = points.first().first
        val span = (points.last().first - t0).coerceAtLeast(1).toFloat()
        val lo = points.minOf { it.second }
        val hi = points.maxOf { it.second }
        val range = (hi - lo).coerceAtLeast(.5f)
        fun x(t: Long) = (t - t0) / span * size.width
        fun y(v: Float) = size.height * .15f + (1f - (v - lo) / range) * size.height * .55f
        val line = Path().apply {
            points.forEachIndexed { i, (t, v) -> if (i == 0) moveTo(x(t), y(v)) else lineTo(x(t), y(v)) }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        val areaBrush = Brush.verticalGradient(listOf(color.copy(alpha = .12f), Color.Transparent))
        onDrawBehind {
            drawPath(area, areaBrush)
            drawPath(line, color.copy(alpha = .32f), style = Stroke(2f))
        }
    })
}

@Composable
private fun RowScope.SceneTile(vm: MainViewModel, key: String, entityId: String, title: String, tile: RoleTile, onMenu: () -> Unit) {
    val started by vm.sceneStartedAt.collectAsStateWithLifecycle()
    val now = rememberNow()
    val startedAt = started?.takeIf { vm.lastSceneId == entityId }
    val recentlyStarted = startedAt != null && now - startedAt < 60_000
    Tile(
        key = key,
        modifier = Modifier.weight(1f).fillMaxHeight().alpha(if (tile is RoleTile.Paused) .55f else 1f),
        style = if (tile is RoleTile.Missing) TileStyle.Dashed else TileStyle.Glass,
        padding = PaddingValues(horizontal = 28.dp, vertical = 22.dp),
        onClick = {
            when (tile) {
                is RoleTile.Missing -> onMenu()
                is RoleTile.Paused -> vm.toast("Home Assistant nicht verbunden", "$title pausiert", warn = true)
                is RoleTile.Ready -> if (tile.action != ActionStatus.Pending) vm.startScene(tile.entity.entityId, title)
            }
        },
        onLongClick = onMenu,
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                IconChip(ShIcon.Film, if (recentlyStarted) Color(0xFFDDD3FF) else Sh.violetText, Sh.chip(Sh.violet, if (recentlyStarted) .32f else .18f))
                if (tile is RoleTile.Ready) {
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Sh.veil(0.102f)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Txt(if (recentlyStarted) "Gestartet" else "Starten", Sh.style(20, FontWeight.SemiBold, Sh.bright))
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(title, Sh.style(28, FontWeight.SemiBold), maxLines = 1)
                val sub = when (tile) {
                    is RoleTile.Paused -> "— pausiert"
                    is RoleTile.Missing -> "Szene fehlt in Home Assistant"
                    is RoleTile.Ready -> when {
                        tile.action is ActionStatus.Failed -> "Start fehlgeschlagen"
                        startedAt != null -> "Zuletzt gestartet ${formatTime(startedAt)}"
                        else -> tile.entity.friendlyName
                    }
                }
                Txt(sub, Sh.style(22, color = Sh.text3), maxLines = 1)
            }
        }
    }
}

@Composable
private fun AppsRow(vm: MainViewModel, settings: AppSettings) {
    val installed by vm.apps.collectAsStateWithLifecycle()
    val favorites = settings.favorites.take(AppSettings.MAX_FAVORITES)
    if (favorites.isEmpty()) {
        Row(
            Modifier.fillMaxWidth().height(150.dp).glass(26).padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            de.sofahub.app.ui.components.EmptyArt(de.sofahub.app.ui.components.Illustration.Apps, 110)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("Deine Lieblings-Apps fehlen noch", Sh.style(30, Sh.W650))
                Txt("Füge bis zu sechs Apps hinzu, um sie direkt von hier zu starten.", Sh.style(22, color = Sh.text2))
            }
            ShButton("app0", "Favoriten hinzufügen", primary = true, icon = ShIcon.Plus) { vm.navigate(Route.Favorites) }
        }
        return
    }
    // Bis zu 12 Favoriten: sechs sichtbar, die Reihe blättert mit dem Fokus weiter.
    LazyRow(Modifier.fillMaxWidth().bleed(PAGE_MARGIN).height(150.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), contentPadding = PaddingValues(horizontal = PAGE_MARGIN)) {
        items(favorites.size, key = { favorites[it].packageName }) { i ->
            val fav = favorites[i]
            val app = installed.firstOrNull { it.packageName == fav.packageName }
            val label = fav.customLabel ?: app?.label ?: fav.packageName.substringAfterLast('.')
            val missing = installed.isNotEmpty() && app == null
            val art = de.sofahub.app.feature.rememberAppArt(vm, fav.packageName)
            val banner = art?.banner != null && !missing
            Tile(
                key = "app$i",
                modifier = Modifier.width(APP_TILE_WIDTH).fillMaxHeight(),
                radius = 26,
                style = if (missing) TileStyle.Dashed else TileStyle.Glass,
                padding = if (banner) PaddingValues(0.dp) else PaddingValues(horizontal = 24.dp, vertical = 22.dp),
                onClick = { if (missing) vm.navigate(Route.Favorites) else vm.launchApp(fav, label) },
                onLongClick = { vm.navigate(Route.Favorites) },
            ) {
                // Echtes TV-Banner der App, sonst Icon + Name (Monogramm nur, wenn die App keine Grafik liefert).
                if (banner) {
                    with(de.sofahub.app.feature.AppBannerScope) { AppBannerIn(art, label) }
                } else Column(Modifier.fillMaxSize().alpha(if (missing) .7f else 1f), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        de.sofahub.app.feature.AppIcon(art, label, fav.packageName)
                        if (missing) Icon(ShIcon.Warning, Sh.warnText, 26)
                    }
                    Column {
                        Txt(label, Sh.style(24, FontWeight.SemiBold))
                        if (missing) Txt("Nicht installiert", Sh.style(19, color = Sh.coralText))
                    }
                }
            }
        }
        if (favorites.size < AppSettings.MAX_FAVORITES) item {
            run {
                Tile(
                    key = "app${favorites.size}",
                    modifier = Modifier.width(APP_TILE_WIDTH).fillMaxHeight(),
                    radius = 26,
                    style = TileStyle.Dashed,
                    onClick = { vm.navigate(Route.Favorites) },
                ) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(ShIcon.Plus, Sh.hint, 30)
                        Txt("Hinzufügen", Sh.style(22, color = Sh.hint))
                    }
                }
            }
        }
    }
}

/** Breite einer App-Kachel: sechs passen nebeneinander auf die volle Breite. */
private val APP_TILE_WIDTH = 264.dp

@Composable
private fun AppsTab(key: String, label: String, icon: ShIcon, selected: Boolean, select: () -> Unit) {
    Tile(
        key, Modifier.height(44.dp), radius = 22, padding = PaddingValues(horizontal = 18.dp),
        style = if (selected) TileStyle.Glass else TileStyle.Plain, onClick = select,
    ) {
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, if (selected) Sh.bright else Sh.hint, 20)
            Txt(label, Sh.style(22, if (selected) FontWeight.SemiBold else FontWeight.Medium, if (selected) Sh.bright else Sh.hint))
        }
    }
}

/** Zuletzt geöffnete, noch installierte Apps – neueste links. */
@Composable
private fun RecentAppsRow(vm: MainViewModel, settings: AppSettings) {
    val installed by vm.apps.collectAsStateWithLifecycle()
    val recent = settings.recentApps.mapNotNull { pkg -> installed.firstOrNull { it.packageName == pkg } }.take(AppSettings.VISIBLE_FAVORITES)
    if (recent.isEmpty()) {
        Row(Modifier.fillMaxWidth().height(150.dp).glass(26).padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            de.sofahub.app.ui.components.EmptyArt(de.sofahub.app.ui.components.Illustration.Recent, 110)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("Noch keine Apps geöffnet", Sh.style(30, Sh.W650))
                Txt("Hier erscheinen die zuletzt genutzten Apps – auch solche, die nicht aus Sofa Hub gestartet wurden.", Sh.style(22, color = Sh.text2))
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        recent.take(AppSettings.VISIBLE_FAVORITES).forEachIndexed { i, app ->
            val art = de.sofahub.app.feature.rememberAppArt(vm, app.packageName)
            val banner = art?.banner != null
            Tile(
                key = "app$i",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                radius = 26,
                padding = if (banner) PaddingValues(0.dp) else PaddingValues(horizontal = 24.dp, vertical = 22.dp),
                onClick = { vm.launchApp(FavoriteApp(app.packageName), app.label) },
            ) {
                if (banner) {
                    with(de.sofahub.app.feature.AppBannerScope) { AppBannerIn(art, app.label) }
                } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    de.sofahub.app.feature.AppIcon(art, app.label, app.packageName)
                    Txt(app.label, Sh.style(24, FontWeight.SemiBold))
                }
            }
        }
        repeat(AppSettings.VISIBLE_FAVORITES - recent.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun HouseBar(vm: MainViewModel, bindings: Bindings, ha: HaSnapshot) {
    val house = DashboardModel.house(bindings, ha)
    // Nur zeigen, was zugeordnet ist: ohne Räume kein Raum-Eintrag, ohne Fenster kein Fenster-Eintrag.
    val showRooms = house.roomNames.isNotEmpty()
    val showWindows = house.windowCount > 0
    val showDevices = house.deviceCount > 0
    if (!showRooms && !showWindows && !showDevices) return
    Row(
        Modifier.fillMaxWidth().height(104.dp).glass(30).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showRooms) HouseRooms(vm, house)
        if (showRooms && (showWindows || showDevices)) Box(Modifier.width(1.dp).height(48.dp).background(Sh.veil(0.102f)))
        if (showWindows) HouseWindows(vm, house)
        if (showWindows && showDevices) Box(Modifier.width(1.dp).height(48.dp).background(Sh.veil(0.102f)))
        if (showDevices) HouseDevices(vm, house)
    }
}

@Composable
private fun RowScope.HouseRooms(vm: MainViewModel, house: HouseSummary) {
    run {
        val roomsText = if (!house.available) "—" else if (house.roomNames.isEmpty()) "Räume" else "${house.roomNames.size} ${if (house.roomNames.size == 1) "Raum" else "Räume"}"
        HouseItem("rooms", ShIcon.Home, Sh.soft, roomsText, if (house.available) house.roomNames.joinToString(", ").ifEmpty { "Keine Raumzuordnung" } else "nicht verfügbar") {
            vm.navigate(Route.SmartHome())
        }
    }
}

@Composable
private fun RowScope.HouseWindows(vm: MainViewModel, house: HouseSummary) {
    run {
        val windowsTitle = when {
            !house.available -> "Fenster —"
            house.windowCount == 0 -> "Fenster"
            house.openWindows.isEmpty() -> "Alle Fenster zu"
            else -> "${house.openWindows.size} ${if (house.openWindows.size == 1) "Fenster" else "Fenster"} offen"
        }
        val windowsSub = when {
            !house.available -> "nicht verfügbar"
            house.windowCount == 0 -> "Keine Fenster zugeordnet"
            house.openWindows.isEmpty() -> "${house.windowCount} überwacht"
            else -> house.openWindows.joinToString(", ")
        }
        HouseItem("windows", ShIcon.Window, if (house.openWindows.isNotEmpty()) Sh.warnText else Sh.soft, windowsTitle, windowsSub) {
            if (house.windowCount == 0) vm.navigate(Route.Picker(PickerTarget.Windows)) else vm.navigate(Route.SmartHome("__windows"))
        }
    }
}

@Composable
private fun RowScope.HouseDevices(vm: MainViewModel, house: HouseSummary) {
    run {
        HouseItem(
            "devices", ShIcon.Devices, Sh.soft,
            if (house.available) "${house.deviceCount} Geräte" else "Geräte —",
            when {
                !house.available -> "nicht verfügbar"
                house.unavailableCount > 0 -> "${house.unavailableCount} nicht erreichbar"
                else -> "alle erreichbar"
            },
            chevron = true,
        ) { vm.navigate(Route.SmartHome()) }
    }
}

@Composable
private fun RowScope.HouseItem(key: String, icon: ShIcon, iconColor: Color, title: String, sub: String, chevron: Boolean = false, onClick: () -> Unit) {
    Tile(
        key = key,
        modifier = Modifier.weight(1f).height(80.dp),
        radius = 22,
        style = TileStyle.Plain,
        padding = PaddingValues(horizontal = 22.dp),
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Icon(icon, iconColor, 32)
            Txt(title, Sh.style(26, FontWeight.SemiBold))
            Txt(sub, Sh.style(22, color = Sh.hint), Modifier.weight(1f))
            if (chevron) Icon(ShIcon.ChevronRight, Sh.hint, 28)
        }
    }
}

@Composable
fun LoadingScreen() {
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 54.dp)) {
        Skeleton(Modifier.width(320.dp).height(76.dp))
        Spacer(Modifier.height(36.dp))
        Row(Modifier.fillMaxWidth().height(360.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Skeleton(Modifier.width(860.dp).fillMaxHeight(), 36)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                repeat(2) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Skeleton(Modifier.weight(1f).fillMaxHeight(), 28)
                        Skeleton(Modifier.weight(1f).fillMaxHeight(), 28)
                    }
                }
            }
        }
        Spacer(Modifier.height(104.dp))
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            repeat(6) { Skeleton(Modifier.weight(1f).fillMaxHeight(), 26) }
        }
    }
}
