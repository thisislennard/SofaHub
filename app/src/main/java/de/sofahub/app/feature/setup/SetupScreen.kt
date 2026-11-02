package de.sofahub.app.feature.setup

import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.HaConnection
import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.core.model.Role
import de.sofahub.app.core.model.message
import de.sofahub.app.feature.MainViewModel
import de.sofahub.app.feature.Route
import de.sofahub.app.feature.dashboard.DashboardModel
import de.sofahub.app.integration.homeassistant.EntityView
import de.sofahub.app.integration.homeassistant.HaResult
import de.sofahub.app.integration.homeassistant.HaUrl
import de.sofahub.app.integration.homeassistant.HaUrlResult
import de.sofahub.app.integration.pairing.PairingState
import de.sofahub.app.ui.components.CheckRow
import de.sofahub.app.ui.components.FocusOnEnter
import de.sofahub.app.ui.components.FocusOnShow
import de.sofahub.app.ui.components.Footer
import de.sofahub.app.ui.components.QrCode
import de.sofahub.app.ui.components.ShButton
import de.sofahub.app.ui.components.ShDialog
import de.sofahub.app.ui.components.ShTextField
import de.sofahub.app.ui.components.Spinner
import de.sofahub.app.ui.components.Txt
import de.sofahub.app.ui.components.glass
import de.sofahub.app.ui.theme.Icon
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(vm: MainViewModel, s: AppSettings, ha: HaSnapshot, step: Int) {
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 54.dp)) {
        Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Sh.logo), contentAlignment = Alignment.Center) {
                Icon(ShIcon.Sofa, Color.White, 30)
            }
            Spacer(Modifier.width(16.dp))
            Txt("Willkommen bei Sofa Hub", Sh.style(30, Sh.W650, letterSpacing = (-0.03).em))
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                StepLabel(1, "Verbinden", step)
                StepLabel(2, "Geräte & Szenen", step)
                StepLabel(3, "Fertig", step)
            }
        }
        when (step) {
            1 -> ConnectStep(vm, s)
            2 -> DevicesStep(vm, s, ha)
            else -> DoneStep(vm)
        }
        Footer(listOf("OK" to "Bestätigen / Text eingeben", "↩" to "Zurück"))
    }
    PairingApprovalDialog(vm)
    }
}

@Composable
private fun StepLabel(n: Int, label: String, current: Int) {
    val done = n < current
    val on = n == current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(if (done) Sh.tealFill else if (on) Sh.text else Color.Transparent)
                .border(2.dp, if (done) Sh.tealFill else if (on) Sh.text else Sh.veil(0.200f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(ShIcon.Check, Color.White, 22) else Txt("$n", Sh.style(22, Sh.W650, if (on) Sh.onText else Sh.muted))
        }
        Txt(label, Sh.style(22, FontWeight.Medium, if (on) Sh.text else if (done) Sh.text2 else Sh.muted))
    }
}

@Composable
private fun ColumnScope.ConnectStep(vm: MainViewModel, s: AppSettings) {
    FocusOnEnter("phone")
    Spacer(Modifier.height(30.dp))
    Txt("Home Assistant verbinden", Sh.style(60, Sh.W650, letterSpacing = (-0.035).em))
    Spacer(Modifier.height(14.dp))
    Txt("Am bequemsten mit dem Handy – oder direkt hier mit der Fernbedienung.", Sh.style(28, color = Sh.text2))
    Spacer(Modifier.height(36.dp))
    val registry = de.sofahub.app.ui.components.LocalFocusRegistry.current
    Row(Modifier.fillMaxWidth().weight(1f)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().glass(36)
                .onKeyEvent { ev ->
                    // Die Knöpfe darunter liegen versetzt – „unten“ vom QR-Knopf führt sonst ins Leere.
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown && registry.last == "phone") { registry.focus("skip"); true } else false
                }
                .padding(44.dp),
        ) {
            PairingPanel(vm, compact = true)
        }
        Column(Modifier.width(90.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.width(1.dp).weight(1f).background(Sh.veil(0.122f)))
            Txt("oder", Sh.style(22, color = Sh.hint))
            Box(Modifier.width(1.dp).weight(1f).background(Sh.veil(0.122f)))
        }
        // Scrollbar: mit "Gefunden"-Zeile, http-Häkchen und Fehlermeldung darf nichts aus der Karte rutschen.
        // Aus dem scrollbaren Bereich findet die Fernbedienung nicht von selbst hinaus: links → Handy-Einrichtung,
        // unten vom Verbinden-Knopf → „Ohne Home Assistant“ (nur wenn sonst niemand die Taste verarbeitet hat).
        Column(
            Modifier.weight(1f).fillMaxHeight().glass(36)
                .onKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when {
                        ev.key == Key.DirectionLeft -> { registry.focus("phone"); true }
                        ev.key == Key.DirectionDown && registry.last == "ha-connect" -> { registry.focus("skip"); true }
                        else -> false
                    }
                }
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 44.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Txt("Direkt am Fernseher", Sh.style(34, Sh.W650, letterSpacing = (-0.02).em))
            ConnectForm(vm, s.ha?.baseUrl ?: "", onConnected = { vm.replace(Route.Setup(2)) })
        }
    }
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        ShButton("skip", "Ohne Home Assistant fortfahren", height = 60, fontSize = 22) {
            vm.updateSettings { it.copy(demoMode = false) }
            vm.replace(Route.Setup(3))
        }
        ShButton("demo", "Demo mit Beispieldaten ansehen", height = 60, fontSize = 22) {
            vm.updateSettings { it.copy(demoMode = true) }
            vm.replace(Route.Setup(3))
        }
    }
    Spacer(Modifier.height(18.dp))
}

/**
 * Adresse + Token eingeben, testen und erst bei Erfolg speichern. Unverschlüsseltes http muss ausdrücklich bestätigt werden.
 */
@Composable
fun ColumnScope.ConnectForm(vm: MainViewModel, initialUrl: String, onConnected: (List<HaEntity>) -> Unit) {
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf("") }
    var allowHttp by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val parsed = HaUrl.parse(url)
    val isHttp = parsed is HaUrlResult.Valid && parsed.isHttp

    val found = remember { androidx.compose.runtime.mutableStateListOf<de.sofahub.app.integration.homeassistant.FoundInstance>() }
    var searching by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.c.haDiscovery.discover().collect { if (found.none { f -> f.url == it.url }) found += it }
        searching = false
    }
    if (found.isNotEmpty() || searching) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (found.isEmpty()) {
                Spinner(22, 3)
                Txt("Suche Home Assistant im Netzwerk …", Sh.style(20, color = Sh.hint))
            } else {
                Txt("Gefunden:", Sh.style(20, color = Sh.hint))
                found.take(2).forEachIndexed { i, f ->
                    ShButton("found$i", "${f.name} · ${f.url.substringAfter("://")}", icon = ShIcon.Home, height = 52, fontSize = 20) {
                        url = f.url
                        allowHttp = f.url.startsWith("http://")
                        error = null
                    }
                }
            }
        }
    }
    ShTextField("ha-url", "Server-Adresse", url, { url = it; error = null }, placeholder = "http://192.168.178.20:8123", height = 64, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri)
    ShTextField("ha-token", "Langlebiges Zugangstoken", token, { token = it.trim(); error = null }, password = true, height = 64)
    if (isHttp) {
        CheckRow("ha-http", allowHttp, "Unverschlüsseltes http erlauben", sub = "Nur für die eigene lokale Instanz im Heimnetz") { allowHttp = !allowHttp }
    } else if (found.isEmpty()) {
        Txt("In Home Assistant unter Profil › Sicherheit › Langlebige Zugangstokens erstellen.", Sh.style(20, color = Sh.hint, lineHeight = 1.4f), maxLines = 2)
    }
    error?.let { Txt(it, Sh.style(22, color = Sh.coralText, lineHeight = 1.35f), maxLines = 3) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        ShButton("ha-connect", if (busy) "Teste Verbindung …" else "Verbinden & weiter", primary = true, height = 72, fontSize = 25, enabled = !busy) {
            if (parsed is HaUrlResult.Invalid) {
                error = parsed.reason
                return@ShButton
            }
            busy = true
            error = null
            scope.launch {
                when (val r = vm.c.ha.saveConnection(url, token, allowHttp)) {
                    is HaResult.Ok -> {
                        vm.updateSettings { it.copy(demoMode = false) }
                        vm.toast("Home Assistant verbunden", "${r.value.size} Entitäten gefunden")
                        onConnected(r.value)
                    }
                    is HaResult.Err -> error = r.error.message()
                }
                busy = false
            }
        }
        if (busy) Spinner()
    }
}

/** QR-Code + Status der Handy-Einrichtung. Der Dienst startet nur auf ausdrücklichen Wunsch und stoppt beim Verlassen. */
@Composable
fun PairingPanel(vm: MainViewModel, compact: Boolean) {
    val state by vm.pairing.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { vm.c.pairing.stop() } }
    val active = state as? PairingState.Waiting
    val awaiting = state as? PairingState.AwaitingApproval
    val approved = state as? PairingState.Approved
    val registry = de.sofahub.app.ui.components.LocalFocusRegistry.current
    androidx.compose.runtime.LaunchedEffect(approved != null) {
        if (approved != null) {
            kotlinx.coroutines.delay(120)
            registry.focus("phone")
        }
    }
    val url = active?.url ?: awaiting?.url ?: approved?.url
    val code = active?.code ?: awaiting?.code ?: approved?.code

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(44.dp), verticalAlignment = Alignment.CenterVertically) {
        if (url != null) {
            QrCode(url, if (compact) 300 else 380)
        } else {
            Box(Modifier.size(if (compact) 300.dp else 380.dp).clip(RoundedCornerShape(24.dp)).background(Sh.veil(0.078f)), contentAlignment = Alignment.Center) {
                Icon(ShIcon.Qr, Sh.muted, 96)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Txt("EMPFOHLEN", Sh.style(22, FontWeight.SemiBold, Sh.tealText, letterSpacing = .08.em))
            Txt("Mit dem Handy", Sh.style(38, Sh.W650, letterSpacing = (-0.02).em))
            Txt(
                "QR-Code scannen, Adresse und Token im Browser eingeben – auch Fotos, Hintergrundbild und Einstellungs-Sicherung.",
                Sh.style(24, color = Sh.text2, lineHeight = 1.4f), maxLines = 4,
            )
            val running = url != null
            when (val st = state) {
                PairingState.Idle, PairingState.Finished, PairingState.Expired, PairingState.Locked, is PairingState.Failed -> {
                    val note = when (st) {
                        PairingState.Finished -> "Handy-Einrichtung abgeschlossen."
                        PairingState.Expired -> "Abgelaufen (10 Minuten)."
                        PairingState.Locked -> "Gesperrt nach Fehlversuchen."
                        is PairingState.Failed -> st.reason
                        else -> null
                    }
                    note?.let { Txt(it, Sh.style(21, color = Sh.hint)) }
                }
                PairingState.NoNetwork -> Txt("Kein lokales Netzwerk gefunden. Ist der Fire TV im Netzwerk?", Sh.style(21, color = Sh.coralText), maxLines = 2)
                else -> {
                    if (!compact && url != null) {
                        Txt("Am PC im Browser öffnen:", Sh.style(20, color = Sh.hint))
                        Txt(url, Sh.style(22, color = Sh.text, mono = true), maxLines = 2)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Txt("Code", Sh.style(21, color = Sh.hint))
                        Txt(code ?: "", Sh.style(34, FontWeight.SemiBold, mono = true, letterSpacing = .2.em))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (approved == null) Spinner(22, 3)
                        Txt(
                            when {
                                approved != null -> approved.lastMessage ?: "Handy verbunden – Eingaben dort vornehmen."
                                awaiting != null -> "Handy meldet sich …"
                                else -> "Warte auf Handy … (gleiches WLAN)"
                            },
                            Sh.style(21, color = if (approved != null) Sh.tealText else Sh.hint), maxLines = 2,
                        )
                    }
                }
            }
            // Immer derselbe Button an derselben Stelle: so behält er beim Zustandswechsel den Fokus.
            ShButton(
                "phone",
                when { running -> "Beenden"; state == PairingState.Idle -> "QR-Code anzeigen"; else -> "Neu starten" },
                icon = if (running) null else ShIcon.Phone, height = 64, fontSize = 23,
            ) { if (running) vm.c.pairing.stop() else vm.c.pairing.start() }
        }
    }
}

/** Bestätigung am Fernseher, auf Bildschirmebene (nicht innerhalb der Karte), damit der Dialog sichtbar ist und den Fokus hält. */
@Composable
fun PairingApprovalDialog(vm: MainViewModel) {
    val state by vm.pairing.collectAsStateWithLifecycle()
    val awaiting = state as? PairingState.AwaitingApproval ?: return
    ShDialog("Handy verbinden?", "Stimmt der Code auf dem Handy mit ${awaiting.code} überein? Nur dann erlauben.", ShIcon.Phone, Sh.tealText, onDismiss = { vm.c.pairing.approve(false) }) {
        FocusOnShow("pair-allow")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val registry = de.sofahub.app.ui.components.LocalFocusRegistry.current
            ShButton("pair-allow", "Erlauben", primary = true) { vm.c.pairing.approve(true); registry.last = "phone" }
            ShButton("pair-deny", "Ablehnen") { vm.c.pairing.approve(false); registry.last = "phone" }
        }
    }
}

@Composable
fun PairingScreen(vm: MainViewModel) {
    FocusOnEnter("phone")
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 54.dp)) {
        de.sofahub.app.ui.components.PageHeader("Einrichtung per Handy", onBack = { vm.back() })
        Spacer(Modifier.height(40.dp))
        Column(Modifier.fillMaxWidth().weight(1f).glass(36).padding(48.dp)) { PairingPanel(vm, compact = false) }
        Spacer(Modifier.height(24.dp))
        Txt(
            "Die Seite auf dem Handy läuft unverschlüsselt (http) im Heimnetz. In fremden Netzen den Token lieber direkt am Fernseher eingeben.",
            Sh.style(20, color = Sh.hint), maxLines = 2,
        )
        Spacer(Modifier.height(16.dp))
        Footer(listOf("OK" to "Auswählen", "↩" to "Beenden"))
    }
    PairingApprovalDialog(vm)
    }
}

private data class Group(val title: String, val items: List<HaEntity>)

@Composable
private fun ColumnScope.DevicesStep(vm: MainViewModel, s: AppSettings, ha: HaSnapshot) {
    FocusOnEnter("dev-0-0", "setup-done")
    val entities = ha.entities.values
    val groups = listOf(
        Group("Licht", entities.filter { it.domain == "light" }),
        Group("Steckdosen & Schalter", entities.filter { it.domain == "switch" || it.domain == "input_boolean" }),
        Group("Klima & Fenster", entities.filter { EntityView.isTemperature(it) || EntityView.isWindowSensor(it) }),
        Group("Szenen & Skripte", entities.filter { it.domain == "scene" || it.domain == "script" }),
    ).map { g -> g.copy(items = g.items.sortedBy { it.friendlyName.lowercase() }) }
    var selected by remember { mutableStateOf((s.smartHomeEntities + s.roles.values + s.windowEntities).toSet()) }

    Spacer(Modifier.height(50.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Txt("Was soll auf die Startseite?", Sh.style(60, Sh.W650, letterSpacing = (-0.035).em))
            Spacer(Modifier.height(14.dp))
            val sub = when {
                ha.connection == HaConnection.Connecting || (ha.connection == HaConnection.Connected && entities.isEmpty()) -> "Lade Geräte aus Home Assistant …"
                ha.connection != HaConnection.Connected -> "Keine Verbindung zu Home Assistant."
                else -> "${groups.sumOf { it.items.size }} Geräte gefunden. Wähle aus, was du vom Sofa aus bedienen willst."
            }
            Txt(sub, Sh.style(28, color = Sh.text2))
        }
        Txt("${selected.size} ausgewählt", Sh.style(24, color = Sh.text2))
    }
    Spacer(Modifier.height(44.dp))
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        groups.forEachIndexed { gi, g ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt(g.title, Sh.style(24, FontWeight.SemiBold, Sh.hint), Modifier.padding(start = 6.dp))
                if (g.items.isEmpty()) Txt("Keine gefunden", Sh.style(21, color = Sh.muted), Modifier.padding(start = 6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(12.dp)) {
                    items(g.items.size) { i ->
                        val e = g.items[i]
                        val room = DashboardModel.roomOf(e.entityId, de.sofahub.app.feature.dashboard.Bindings.of(s, false), ha) ?: ""
                        CheckRow("dev-$gi-$i", e.entityId in selected, e.friendlyName, sub = room.ifEmpty { null }) {
                            selected = if (e.entityId in selected) selected - e.entityId else selected + e.entityId
                        }
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.End)) {
        ShButton("setup-back", "Zurück") { vm.replace(Route.Setup(1)) }
        ShButton("setup-done", "Fertig", primary = true) {
            vm.updateSettings { applySelection(it, selected, ha) }
            vm.replace(Route.Setup(3))
        }
    }
}

/**
 * Übernimmt die Auswahl: Fenster-Sensoren als Fenster, Rest auf die Smart-Home-Seite. Freie Rollen werden mit der ersten
 * passenden Auswahl vorbelegt (jederzeit in den Einstellungen änderbar), bestehende Rollen bleiben unangetastet.
 */
fun applySelection(s: AppSettings, selected: Set<String>, ha: HaSnapshot): AppSettings {
    val chosen = selected.mapNotNull { ha.entities[it] }.sortedBy { it.friendlyName.lowercase() }
    val windows = chosen.filter { EntityView.isWindowSensor(it) }.map { it.entityId }
    val others = chosen.filter { !EntityView.isWindowSensor(it) && it.domain != "scene" && it.domain != "script" }.map { it.entityId }
    val roles = s.roles.toMutableMap()
    fun fill(role: Role) {
        if (roles[role] == null) DashboardModel.candidatesFor(role, chosen).firstOrNull()?.let { roles[role] = it.entityId }
    }
    fill(Role.LIGHT); fill(Role.TV_PLUG); fill(Role.TEMPERATURE); fill(Role.MOVIE_SCENE)
    if (roles[Role.WEATHER] == null) DashboardModel.candidatesFor(Role.WEATHER, ha.entities.values).firstOrNull()?.let { roles[Role.WEATHER] = it.entityId }
    return s.copy(roles = roles, windowEntities = windows, smartHomeEntities = others)
}

@Composable
private fun ColumnScope.DoneStep(vm: MainViewModel) {
    FocusOnEnter("finish")
    Spacer(Modifier.weight(1f))
    Box(Modifier.size(120.dp).clip(RoundedCornerShape(36.dp)).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF4ADE80), Sh.tealFill))), contentAlignment = Alignment.Center) {
        Icon(ShIcon.Check, Color.White, 64)
    }
    Spacer(Modifier.height(32.dp))
    Txt("Alles bereit", Sh.style(60, Sh.W650, letterSpacing = (-0.035).em))
    Spacer(Modifier.height(14.dp))
    Txt("Geräte, Favoriten, Hintergrund und Updates findest du jederzeit in den Einstellungen.", Sh.style(28, color = Sh.text2))
    Spacer(Modifier.height(40.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        ShButton("finish", "Zur Startseite", primary = true, height = 72, fontSize = 25) { vm.completeSetup() }
        ShButton("fav", "Favoriten auswählen", height = 72, fontSize = 25) {
            vm.completeSetup()
            vm.navigate(Route.Favorites)
        }
    }
    Spacer(Modifier.weight(1f))
}
