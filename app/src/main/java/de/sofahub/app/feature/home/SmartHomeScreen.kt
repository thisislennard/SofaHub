package de.sofahub.app.feature.home

import de.sofahub.app.core.model.effectiveHomeTiles
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import de.sofahub.app.core.model.ActionStatus
import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.Availability
import de.sofahub.app.core.model.HaConnection
import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.feature.MainViewModel
import de.sofahub.app.feature.PickerTarget
import de.sofahub.app.feature.Route
import de.sofahub.app.feature.dashboard.Bindings
import de.sofahub.app.feature.dashboard.DashboardModel
import de.sofahub.app.feature.dashboard.formatTime
import de.sofahub.app.feature.dashboard.rememberNow
import de.sofahub.app.feature.islandStatus
import de.sofahub.app.integration.homeassistant.EntityView
import de.sofahub.app.integration.homeassistant.MediaAction
import de.sofahub.app.ui.components.BackButton
import de.sofahub.app.ui.components.Dot
import de.sofahub.app.ui.components.FocusOnEnter
import de.sofahub.app.ui.components.FocusOnShow
import de.sofahub.app.ui.components.Footer
import de.sofahub.app.ui.components.IconChip
import de.sofahub.app.ui.components.IslandTone
import de.sofahub.app.ui.components.LocalFocusRegistry
import de.sofahub.app.ui.components.ShButton
import de.sofahub.app.ui.components.ShDialog
import de.sofahub.app.ui.components.Spinner
import de.sofahub.app.ui.components.SwitchPill
import de.sofahub.app.ui.components.Tile
import de.sofahub.app.ui.components.TileStyle
import de.sofahub.app.ui.components.Txt
import de.sofahub.app.ui.components.glass
import de.sofahub.app.ui.theme.Icon
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon

private const val ALL = "__all"
private const val WINDOWS = "__windows"
private const val VIEW_ROOMS = "__rooms"
private const val VIEW_PLAN = "__plan"

private enum class HomeView { Rooms, Plan, Devices }

@Composable
fun SmartHomeScreen(vm: MainViewModel, s: AppSettings, ha: HaSnapshot, initialRoom: String?) {
    val bindings = remember(s, vm.c.ha.isDemo) { Bindings.of(s, vm.c.ha.isDemo) }
    var room by remember { mutableStateOf(initialRoom ?: ALL) }
    // Ohne Vorauswahl starten wir mit den Raumkarten; ein Raum (oder „Fenster“ von der Startseite) öffnet die Geräte.
    var viewChoice by remember { mutableStateOf(if (initialRoom == null) HomeView.Rooms else HomeView.Devices) }
    var panelEntity by remember {
        mutableStateOf(
            s.effectiveHomeTiles(bindings.roles, vm.c.ha.isDemo).firstOrNull { it.entityId.startsWith("light.") }?.entityId
                ?: (bindings.smartHome + bindings.roles.values).firstOrNull { it.startsWith("light.") },
        )
    }
    var dialog by remember { mutableStateOf<HaEntity?>(null) }
    val connected = ha.connection == HaConnection.Connected
    val now = rememberNow()

    val ids = (bindings.smartHome + bindings.windows).distinct()
    val rooms = ids.mapNotNull { DashboardModel.roomOf(it, bindings, ha) }.distinct()
    val visible = when (room) {
        ALL -> ids
        WINDOWS -> bindings.windows
        else -> ids.filter { DashboardModel.roomOf(it, bindings, ha) == room }
    }
    val roomInfos = Rooms.of(bindings, ha)
    val view = if (roomInfos.isEmpty()) HomeView.Devices else viewChoice
    FocusOnEnter("room0", "plan0", "lamp", "dev0", "pill-all")

    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 54.dp)) {
        Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton { vm.back() }
            Spacer(Modifier.width(20.dp))
            Txt("Smart Home", Sh.style(46, Sh.W650, letterSpacing = (-0.03).em))
            Spacer(Modifier.weight(1f))
            val status = islandStatus(ha, vm.c.ha.isDemo)
            Dot(if (status.tone == IslandTone.Ok) Sh.teal else if (status.tone == IslandTone.Warn) Sh.coral else Sh.muted)
            Spacer(Modifier.width(10.dp))
            Txt(status.text, Sh.style(22, color = Sh.text3))
            Spacer(Modifier.width(28.dp))
            Txt(formatTime(now), Sh.style(32, FontWeight.SemiBold))
        }
        Spacer(Modifier.height(30.dp))
        val views = if (roomInfos.isNotEmpty()) listOf(VIEW_ROOMS to "Räume", VIEW_PLAN to "Hausplan") else emptyList()
        val pills = views + listOf(ALL to "Alle Geräte") + rooms.map { it to it } + (if (bindings.windows.isNotEmpty()) listOf(WINDOWS to "Fenster") else emptyList())
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) {
            items(pills.size) { i ->
                val (id, label) = pills[i]
                val count = when (id) {
                    VIEW_ROOMS, VIEW_PLAN -> roomInfos.size
                    ALL -> ids.size
                    WINDOWS -> bindings.windows.size
                    else -> ids.count { DashboardModel.roomOf(it, bindings, ha) == id }
                }
                val sel = when (id) {
                    VIEW_ROOMS -> view == HomeView.Rooms
                    VIEW_PLAN -> view == HomeView.Plan
                    else -> view == HomeView.Devices && room == id
                }
                Tile(
                    key = if (id == ALL) "pill-all" else "pill-$id",
                    modifier = Modifier.height(60.dp),
                    radius = 30,
                    style = if (sel) TileStyle.Solid else TileStyle.Glass,
                    background = if (sel) Brush.linearGradient(listOf(Sh.text, Sh.text)) else null,
                    padding = PaddingValues(horizontal = 30.dp),
                    onClick = {
                        when (id) {
                            VIEW_ROOMS -> viewChoice = HomeView.Rooms
                            VIEW_PLAN -> viewChoice = HomeView.Plan
                            else -> { room = id; viewChoice = HomeView.Devices }
                        }
                    },
                ) {
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        Txt(label, Sh.style(24, FontWeight.SemiBold, if (sel) Sh.onText else Sh.text))
                        Spacer(Modifier.width(12.dp))
                        Txt("$count", Sh.style(20, FontWeight.Medium, (if (sel) Sh.onText else Sh.text).copy(alpha = .7f)))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        if (!connected && !vm.c.ha.isDemo) OfflineBanner(vm, ha)
        val openRoom: (String) -> Unit = { room = it; viewChoice = HomeView.Devices }
        if (view == HomeView.Rooms) RoomCards(roomInfos, Modifier.fillMaxWidth().weight(1f), openRoom)
        if (view == HomeView.Plan) {
            HousePlan(roomInfos, Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp), openRoom)
            Spacer(Modifier.height(14.dp))
            PlanLegend()
        }
        if (view == HomeView.Devices) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            LampPanel(vm, panelEntity?.let { ha.entities[it] }, ha, connected, Modifier.width(700.dp).fillMaxHeight()) {
                vm.navigate(Route.Picker(PickerTarget.SmartHome))
            }
            if (visible.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxHeight().glass(32).padding(44.dp), verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)) {
                    de.sofahub.app.ui.components.EmptyArt(if (ids.isEmpty()) de.sofahub.app.ui.components.Illustration.Devices else de.sofahub.app.ui.components.Illustration.Search, 150)
                    Txt(if (ids.isEmpty()) "Noch keine Geräte ausgewählt" else "Keine Geräte in diesem Bereich", Sh.style(34, Sh.W650))
                    Txt("Wähle aus, welche Home-Assistant-Geräte hier erscheinen.", Sh.style(23, color = Sh.text2))
                    ShButton("dev0", "Geräte auswählen", primary = true, icon = ShIcon.Plus) { vm.navigate(Route.Picker(PickerTarget.SmartHome)) }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    items(visible.size, key = { visible[it] }) { i ->
                        val id = visible[i]
                        DeviceTile(vm, "dev$i", id, ha.entities[id], ha, connected, onSelectLight = { panelEntity = id }, onDialog = { dialog = it })
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
        Footer(
            if (view == HomeView.Devices) listOf("▲ ▼" to "Bereich wechseln", "OK" to "Schalten", "OK lang" to "Licht im Regler", "↩" to "Startseite")
            else listOf("◀ ▶ ▲ ▼" to "Raum wählen", "OK" to "Raum öffnen", "↩" to "Startseite"),
            badge = if (vm.c.ha.isDemo) "Demo · Simulierte Geräte" else null,
        )
    }
    dialog?.let { e -> DeviceDialog(vm, e, ha) { dialog = null } }
}

@Composable
private fun OfflineBanner(vm: MainViewModel, ha: HaSnapshot) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 24.dp).clip(RoundedCornerShape(22.dp)).background(Color(0x24FF7D6B))
            .border(1.dp, Color(0x66FF7D6B), RoundedCornerShape(22.dp)).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(ShIcon.WifiOff, Sh.coralSoft, 34)
        Column(Modifier.weight(1f)) {
            Txt("Home Assistant antwortet nicht", Sh.style(24, Sh.W650))
            val last = if (ha.lastSyncEpochMs > 0) " · letzter Kontakt ${formatTime(ha.lastSyncEpochMs)}" else ""
            Txt("Smart-Home-Kacheln sind pausiert, Werte können veraltet sein. Streaming und Spiegeln funktionieren weiter.$last", Sh.style(20, color = Sh.soft))
        }
        ShButton("reconnect", "Erneut verbinden", primary = true, height = 56, fontSize = 21) { vm.reconnectHa() }
    }
}

@Composable
private fun LampPanel(vm: MainViewModel, entity: HaEntity?, ha: HaSnapshot, connected: Boolean, modifier: Modifier, onAssign: () -> Unit) {
    Column(modifier.glass(36).padding(horizontal = 44.dp, vertical = 40.dp)) {
        if (entity == null) {
            Txt("Kein Licht zugeordnet", Sh.style(34, Sh.W650))
            Spacer(Modifier.height(12.dp))
            Txt("Lege fest, welches Licht hier mit Helligkeitsregler erscheint.", Sh.style(23, color = Sh.text2), maxLines = 2)
            Spacer(Modifier.weight(1f))
            ShButton("lamp", "Licht wählen", primary = true, onClick = onAssign)
            return@Column
        }
        val action = ha.actions[entity.entityId]
        val available = connected && entity.availability == Availability.NORMAL
        val on = EntityView.isOn(entity)
        val dimmable = EntityView.isDimmable(entity)
        var target by remember(entity.entityId) { mutableStateOf<Int?>(null) }
        val current = EntityView.brightnessPercent(entity) ?: 0
        val shown = target ?: if (on) current else 0
        val room = DashboardModel.roomOf(entity.entityId, Bindings.of(AppSettings(), false), ha)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            IconChip(ShIcon.Bulb, if (on) Sh.sun else Sh.muted, if (on) Color(0x3DFFC94D) else Sh.veil(0.078f), 76, 42, 24)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(entity.friendlyName, Sh.style(34, Sh.W650))
                Txt(listOfNotNull(room, if (!available) EntityView.stateLabel(entity) else null).joinToString(" · ").ifEmpty { entity.entityId }, Sh.style(23, color = Sh.text3))
            }
            if (action == ActionStatus.Pending) Spinner() else SwitchPill(on && available)
        }
        Spacer(Modifier.weight(1f))
        if (dimmable) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                Txt("Helligkeit", Sh.style(26, color = Sh.text2))
                Row(verticalAlignment = Alignment.Bottom) {
                    Txt("$shown", Sh.style(96, FontWeight.SemiBold, letterSpacing = (-0.045).em, lineHeight = 1f))
                    Txt("%", Sh.style(44, color = Sh.text3), Modifier.padding(start = 4.dp, bottom = 8.dp))
                }
            }
            Spacer(Modifier.height(26.dp))
            BrightnessSlider(shown, available, onChange = { v ->
                target = v
                vm.setBrightness(entity.entityId, v)
            }, onToggle = { if (available) vm.toggle(entity.entityId, on, entity.friendlyName) })
            Spacer(Modifier.height(30.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StepButton("dim-down", ShIcon.ChevronLeft, available) { val v = (shown - 10).coerceAtLeast(0); target = v; vm.setBrightness(entity.entityId, v) }
                StepButton("dim-up", ShIcon.ChevronRight, available) { val v = (shown + 10).coerceAtMost(100); target = v; vm.setBrightness(entity.entityId, v) }
                Txt("◀ ▶ in 10-%-Schritten · gehalten 5 % · OK schaltet ein/aus", Sh.style(21, color = Sh.hint), Modifier.padding(start = 10.dp))
            }
        } else {
            Txt(if (available) "Dieses Licht kann nur ein- und ausgeschaltet werden." else "Gerät nicht erreichbar.", Sh.style(24, color = Sh.text2))
            Spacer(Modifier.height(24.dp))
            ShButton("lamp", if (on) "Ausschalten" else "Einschalten", primary = true, enabled = available) { vm.toggle(entity.entityId, on, entity.friendlyName) }
        }
    }
}

/** Regler: ◀ ▶ ändern um 10 %, gehalten um 5 %, OK schaltet. Fokusrahmen liegt am ganzen Regler. */
@Composable
private fun BrightnessSlider(value: Int, enabled: Boolean, onChange: (Int) -> Unit, onToggle: () -> Unit) {
    val registry = LocalFocusRegistry.current
    val requester = remember(registry) { registry.requester("lamp") }
    var focused by remember { mutableStateOf(false) }
    val fill by animateFloatAsState(value / 100f, tween(250), label = "fill")
    Box(
        Modifier.fillMaxWidth().height(84.dp)
            .drawBehind {
                if (focused) {
                    val o = 10.dp.toPx()
                    drawRoundRect(Sh.focusRing, Offset(-o + 2.dp.toPx(), -o + 2.dp.toPx()), Size(size.width + 2 * o - 4.dp.toPx(), size.height + 2 * o - 4.dp.toPx()), CornerRadius(34.dp.toPx()), style = Stroke(4.dp.toPx()))
                }
            }
            .clip(RoundedCornerShape(26.dp)).background(Sh.veil(0.078f))
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) registry.last = "lamp" }
            .onPreviewKeyEvent { ev ->
                val code = ev.nativeKeyEvent.keyCode
                if (ev.type == KeyEventType.KeyDown && (code == AndroidKeyEvent.KEYCODE_DPAD_LEFT || code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT)) {
                    if (enabled) {
                        val step = if (ev.nativeKeyEvent.repeatCount > 0) 5 else 10
                        val dir = if (code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                        onChange((value + dir * step).coerceIn(0, 100))
                    }
                    true
                } else if (ev.type == KeyEventType.KeyUp && (code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER)) {
                    onToggle(); true
                } else code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER
            }
            .focusable(),
    ) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(fill.coerceIn(0.001f, 1f)).clip(RoundedCornerShape(26.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFFFF8A4C), Sh.sun))).alpha(if (enabled) 1f else .4f),
        )
        Row(Modifier.fillMaxSize()) {
            repeat(10) { Box(Modifier.weight(1f).fillMaxHeight().drawBehind { drawLine(Color(0x2E000000), Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }) }
        }
    }
}

@Composable
private fun StepButton(key: String, icon: ShIcon, enabled: Boolean, onClick: () -> Unit) {
    Tile(key, Modifier.size(72.dp), radius = 22, enabled = enabled, onClick = onClick) {
        Box(Modifier.align(Alignment.Center)) { Icon(icon, Sh.bright.copy(alpha = if (enabled) 1f else .4f), 30) }
    }
}

@Composable
private fun DeviceTile(
    vm: MainViewModel, key: String, id: String, entity: HaEntity?, ha: HaSnapshot, connected: Boolean,
    onSelectLight: () -> Unit, onDialog: (HaEntity) -> Unit,
) {
    val action = ha.actions[id]
    val pending = action == ActionStatus.Pending
    val missing = entity == null
    val unavailable = entity?.availability == Availability.UNAVAILABLE
    val paused = !connected
    val on = entity != null && EntityView.isOn(entity)
    Tile(
        key = key,
        modifier = Modifier.fillMaxWidth().height(208.dp).alpha(if (paused) .55f else if (unavailable || missing) .72f else 1f),
        radius = 26,
        style = if (unavailable || missing) TileStyle.Dashed else TileStyle.Glass,
        padding = PaddingValues(horizontal = 24.dp, vertical = 22.dp),
        onLongClick = { if (entity?.domain == "light") { onSelectLight(); vm.toast("${entity.friendlyName} im Regler") } },
        onClick = click@{
            if (entity == null) return@click vm.toast("Gerät fehlt in Home Assistant", "Zuordnung in den Einstellungen prüfen", warn = true)
            if (paused) return@click vm.toast("Home Assistant nicht verbunden", warn = true)
            if (unavailable) return@click onDialog(entity)
            if (pending) return@click
            when {
                EntityView.isSwitchable(entity) -> vm.toggle(id, on, entity.friendlyName)
                entity.domain == "scene" || entity.domain == "script" -> vm.startScene(id, entity.friendlyName)
                entity.domain == "media_player" -> onDialog(entity)
                else -> vm.toast(entity.friendlyName, EntityView.stateLabel(entity), icon = ShIcon.Info)
            }
        },
    ) {
        val (icon, fg, bg) = visuals(entity, on)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                IconChip(icon, if (unavailable || missing) Sh.muted else fg, if (unavailable || missing) Sh.veil(0.059f) else bg, 52, 28, 16)
                when {
                    pending -> Spinner()
                    unavailable || missing -> Icon(ShIcon.Warning, Sh.warnText, 30)
                    EntityView.isSwitchable(entity) -> SwitchPill(on)
                    (entity.domain == "climate" || entity.domain == "sensor") ->
                        Txt((if (entity.domain == "climate") EntityView.temperatureText(entity) else EntityView.sensorText(entity)) ?: "—", Sh.style(36, FontWeight.SemiBold, letterSpacing = (-0.03).em, lineHeight = 1f))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Txt(entity?.friendlyName ?: id, Sh.style(25, FontWeight.SemiBold))
                val (label, color) = when {
                    missing -> "Fehlt in Home Assistant" to Sh.coralText
                    pending -> "Schaltet …" to Sh.tealText
                    action is ActionStatus.Failed -> "Fehlgeschlagen" to Sh.coralText
                    paused -> "— nicht verfügbar" to Sh.text3
                    entity.domain == "climate" -> (EntityView.climateDetail(entity) ?: entity.state) to Sh.text3
                    entity.domain == "binary_sensor" && on -> EntityView.stateLabel(entity) to Sh.coralText
                    unavailable -> "Nicht erreichbar" to Sh.coralText
                    else -> EntityView.stateLabel(entity) to Sh.text3
                }
                Txt(label + if (ha.stale && !missing && !pending) " · veraltet" else "", Sh.style(21, color = color))
            }
        }
    }
}

private fun visuals(e: HaEntity?, on: Boolean): Triple<ShIcon, Color, Color> {
    val off = Triple(ShIcon.Devices, Sh.muted, Sh.veil(0.078f))
    e ?: return off
    return when (e.domain) {
        "light" -> if (on) Triple(ShIcon.Bulb, Sh.sun, Color(0x3DFFC94D)) else off.copy(first = ShIcon.Bulb)
        "switch", "input_boolean", "fan" -> if (on) Triple(ShIcon.Plug, Sh.coralSoft, Color(0x3DFF7D6B)) else off.copy(first = ShIcon.Plug)
        "climate" -> Triple(ShIcon.Thermo, Sh.orangeText, Color(0x38FFA24C))
        "sensor" -> if (EntityView.isTemperature(e)) Triple(ShIcon.Thermo, Sh.orangeText, Color(0x38FFA24C)) else Triple(ShIcon.Info, Sh.skyText, Color(0x3860A5FA))
        "binary_sensor" -> if (on) Triple(ShIcon.Window, Sh.coralText, Color(0x38FF7D6B)) else Triple(ShIcon.Window, Sh.skyText, Color(0x3860A5FA))
        "scene", "script" -> Triple(ShIcon.Film, Sh.violetText, Color(0x2EA78BFA))
        "media_player" -> Triple(ShIcon.Laptop, Sh.tealText, Color(0x292DD4BF))
        else -> off
    }
}

@Composable
private fun DeviceDialog(vm: MainViewModel, e: HaEntity, ha: HaSnapshot, onClose: () -> Unit) {
    if (e.domain == "media_player" && e.availability == Availability.NORMAL) {
        val supported = EntityView.mediaSupports(e)
        ShDialog(e.friendlyName, "Nur die Funktionen, die das Gerät meldet. " + EntityView.stateLabel(e), ShIcon.Laptop, Sh.tealText, onDismiss = onClose) {
            FocusOnShow("mp-close")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (MediaAction.POWER_ON in supported && e.state == "off") ShButton("mp-on", "Ein", icon = ShIcon.Power) { vm.media(e.entityId, MediaAction.POWER_ON) }
                if (MediaAction.POWER_OFF in supported && e.state != "off") ShButton("mp-off", "Aus", icon = ShIcon.Power) { vm.media(e.entityId, MediaAction.POWER_OFF) }
                if (MediaAction.VOLUME_DOWN in supported) ShButton("mp-vd", "Leiser", icon = ShIcon.VolumeDown) { vm.media(e.entityId, MediaAction.VOLUME_DOWN) }
                if (MediaAction.VOLUME_UP in supported) ShButton("mp-vu", "Lauter", icon = ShIcon.VolumeUp) { vm.media(e.entityId, MediaAction.VOLUME_UP) }
                ShButton("mp-close", "Schließen", primary = true, onClick = onClose)
            }
            val sources = EntityView.mediaSources(e)
            if (sources.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp, horizontal = 6.dp)) {
                    items(sources.size) { i -> ShButton("src$i", sources[i], height = 56, fontSize = 20) { vm.c.ha.selectSource(e.entityId, sources[i]); onClose() } }
                }
            }
        }
        return
    }
    val since = e.lastChanged.takeIf { it.isNotEmpty() }?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
    ShDialog(
        "${e.friendlyName} nicht erreichbar",
        (since?.let { "Seit ${formatTime(it)} keine Antwort. " } ?: "") + "Ist das Gerät mit Strom versorgt und in Home Assistant verbunden?",
        ShIcon.Warning,
        onDismiss = onClose,
    ) {
        FocusOnShow("dlg-close")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShButton("dlg-retry", "Erneut verbinden", primary = true) { vm.reconnectHa(); onClose() }
            ShButton("dlg-close", "Schließen", onClick = onClose)
        }
    }
}
