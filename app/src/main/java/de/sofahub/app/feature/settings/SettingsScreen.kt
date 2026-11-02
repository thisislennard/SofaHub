package de.sofahub.app.feature.settings

import de.sofahub.app.ui.components.enterFirst
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import de.sofahub.app.core.model.message
import de.sofahub.app.feature.dashboard.title
import de.sofahub.app.core.model.effectiveHomeTiles
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sofahub.app.BuildConfig
import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.HaConnection
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.core.model.Role
import de.sofahub.app.core.model.code
import de.sofahub.app.core.storage.TokenResult
import de.sofahub.app.core.storage.maskSecret
import de.sofahub.app.feature.AutomationSlot
import de.sofahub.app.feature.MainViewModel
import de.sofahub.app.integration.automation.AutomationRunner
import de.sofahub.app.feature.PickerTarget
import de.sofahub.app.feature.Route
import de.sofahub.app.feature.SettingsSection
import de.sofahub.app.feature.dashboard.formatTime
import de.sofahub.app.feature.islandStatus
import de.sofahub.app.integration.updates.UpdateState
import de.sofahub.app.ui.components.CheckRow
import de.sofahub.app.ui.components.Dot
import de.sofahub.app.ui.components.FocusOnEnter
import de.sofahub.app.ui.components.FocusOnShow
import de.sofahub.app.ui.components.Footer
import de.sofahub.app.ui.components.IslandTone
import de.sofahub.app.ui.components.PageHeader
import de.sofahub.app.ui.components.ShButton
import de.sofahub.app.ui.components.ShDialog
import de.sofahub.app.ui.components.ShTextField
import de.sofahub.app.ui.components.Tile
import de.sofahub.app.ui.components.TileStyle
import de.sofahub.app.ui.components.Txt
import de.sofahub.app.ui.components.glass
import de.sofahub.app.ui.theme.Icon
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon

private data class NavItem(val section: SettingsSection?, val label: String, val icon: ShIcon, val route: Route? = null)

@Composable
fun SettingsScreen(vm: MainViewModel, s: AppSettings, ha: HaSnapshot, initial: SettingsSection, initialSubpage: Route? = null) {
    var section by remember { mutableStateOf(initial) }
    /** Angewählter Eintrag mit eigener Unterseite: rechts erscheint eine Vorschau statt der vorigen Seite. */
    var preview by remember { mutableStateOf(initialSubpage) }
    val update by vm.updates.collectAsStateWithLifecycle()
    val items = listOf(
        NavItem(SettingsSection.HomeAssistant, "Home Assistant", ShIcon.Home),
        NavItem(SettingsSection.Home, "Startseite", ShIcon.Layout),
        NavItem(SettingsSection.Assignment, "Gerätezuordnung", ShIcon.Assign),
        NavItem(SettingsSection.Appearance, "Darstellung", ShIcon.Palette),
        NavItem(null, "Favoriten", ShIcon.Grid, Route.Favorites),
        NavItem(SettingsSection.Photos, "Fotos & Notiz", ShIcon.Image),
        NavItem(SettingsSection.Automations, "Automationen", ShIcon.Power),
        NavItem(SettingsSection.Kiosk, "Start & Kiosk", ShIcon.Home),
        NavItem(null, "Updates", ShIcon.Download, Route.Updates),
        NavItem(SettingsSection.Info, "App-Informationen", ShIcon.Info),
    )
    // Kachel umbenennen: Dialog auf Bildschirmebene (in einer Karte würde er zusammengedrückt).
    var tileRename by remember { mutableStateOf<Int?>(null) }
    var wifiEdit by remember { mutableStateOf(false) }
    FocusOnEnter(items.firstOrNull { it.route != null && it.route == initialSubpage }?.let { "nav-${it.label}" } ?: "nav-${initial.name}")
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 54.dp)) {
        PageHeader("Einstellungen", onBack = { vm.back() })
        Spacer(Modifier.height(36.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            // Kommt der Fokus von rechts (oder oben) in die Liste, landet er auf dem gerade offenen Bereich – nicht auf
            // dem Eintrag, der zufällig auf gleicher Höhe liegt (das hätte den Bereich gewechselt).
            val navRegistry = de.sofahub.app.ui.components.LocalFocusRegistry.current
            val currentNavKey = items.firstOrNull { if (preview != null) it.route == preview else it.section == section }
                ?.let { "nav-${it.section?.name ?: it.label}" } ?: "nav-${section.name}"
            Column(
                Modifier.width(440.dp).fillMaxHeight()
                    .focusProperties { onEnter = { navRegistry.requester(currentNavKey).requestFocus() } }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEach { item ->
                    val sel = if (preview != null) item.route == preview else item.section == section
                    Tile(
                        key = "nav-${item.section?.name ?: item.label}",
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        radius = 22,
                        style = TileStyle.Plain,
                        background = if (sel) androidx.compose.ui.graphics.Brush.linearGradient(listOf(Sh.veil(0.102f), Sh.veil(0.102f))) else null,
                        focusScale = 1.03f,
                        padding = PaddingValues(horizontal = 22.dp),
                        onFocus = { preview = item.route; item.section?.let { section = it } },
                        onClick = { if (item.route != null) vm.navigateFromSettings(section, item.route, item.route) else item.section?.let { preview = null; section = it } },
                    ) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Icon(item.icon, if (sel) Sh.text else Sh.soft, 28)
                            Txt(item.label, Sh.style(25, if (sel) Sh.W650 else FontWeight.Medium, if (sel) Sh.text else Sh.soft), Modifier.weight(1f))
                            if (item.route == Route.Updates && update is UpdateState.Available) {
                                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Sh.teal).padding(horizontal = 12.dp, vertical = 5.dp)) {
                                    Txt("1 neu", Sh.style(18, Sh.W650, Color(0xFF0B2E2A)))
                                }
                            }
                            if (item.route != null) Icon(ShIcon.ChevronRight, Sh.muted, 24)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Txt(
                    "Sofa Hub ${BuildConfig.VERSION_NAME}${if (BuildConfig.DEBUG) " (Debug)" else ""}${if (vm.c.ha.isDemo) " · Demo" else ""}\n${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
                    Sh.style(19, color = Sh.muted, lineHeight = 1.5f), Modifier.padding(horizontal = 22.dp), maxLines = 2,
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                if (preview != null) SubpagePreview(vm, s, section, preview!!, update)
                else when (section) {
                    SettingsSection.HomeAssistant -> HaSection(vm, s, ha)
                    SettingsSection.Assignment -> AssignmentSection(vm, s, ha)
                    SettingsSection.Home -> HomeLayoutSection(vm, s, ha, onRename = { tileRename = it }, onWifi = { wifiEdit = true })
                    SettingsSection.Appearance -> AppearanceSection(vm, s)
                    SettingsSection.Photos -> PhotosSection(vm, s)
                    SettingsSection.Automations -> AutomationsSection(vm, s, ha)
                    SettingsSection.Kiosk -> KioskSection(vm, s)
                    SettingsSection.Info -> InfoSection(vm, s, ha)
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        Footer(listOf("◀ ▶" to "Liste / Details", "OK" to "Bearbeiten", "↩" to "Zurück"), badge = if (vm.c.ha.isDemo) "Demo · Beispieldaten" else "Zugangsdaten maskiert")
    }
    tileRename?.let { i -> s.effectiveHomeTiles().getOrNull(i)?.let { t -> de.sofahub.app.feature.dashboard.TileNameDialog(vm, ha, i, t) { tileRename = null } } }
    if (wifiEdit) de.sofahub.app.feature.dashboard.GuestWifiEditDialog(vm, s.guestWifi) { wifiEdit = false }
    }
}


/** Kurzinfo zu Einträgen mit eigener Seite (Favoriten, Updates); OK in der Liste oder „Öffnen“ wechselt dorthin. */
@Composable
private fun ColumnScope.SubpagePreview(vm: MainViewModel, s: AppSettings, section: SettingsSection, route: Route, update: UpdateState) {
    val (title, lines, button) = when (route) {
        Route.Favorites -> Triple(
            "Favoriten",
            listOf(
                if (s.favorites.isEmpty()) "Noch keine Favoriten auf der Startseite." else if (s.favorites.size == 1) "1 App auf der Startseite." else "${s.favorites.size} Apps auf der Startseite.",
                "Apps hinzufügen, entfernen und die Reihenfolge ändern.",
            ),
            "Favoriten bearbeiten",
        )
        else -> Triple(
            "Updates",
            listOf(
                "Installiert: ${BuildConfig.VERSION_NAME}",
                when (update) {
                    is UpdateState.Available -> "Neue Version ${update.offer.manifest.versionName} verfügbar."
                    is UpdateState.UpToDate -> "Sofa Hub ist aktuell."
                    UpdateState.Checking -> "Suche nach Updates …"
                    is UpdateState.Error -> "Letzte Prüfung fehlgeschlagen – Details auf der Seite."
                    UpdateState.Idle -> if (s.lastUpdateCheckEpochMs > 0) "Zuletzt geprüft: ${java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(java.util.Date(s.lastUpdateCheckEpochMs))}" else "Noch nicht geprüft."
                    is UpdateState.Installed -> "Version ${update.versionName} wurde installiert."
                    else -> "Update läuft – Details auf der Seite."
                },
                if (s.autoUpdateCheck) "Sofa Hub sucht einmal täglich selbst nach Updates." else "Automatische Suche ist aus.",
            ),
            "Updates öffnen",
        )
    }
    Card {
        Txt(title, Sh.style(32, Sh.W650))
        Spacer(Modifier.height(16.dp))
        lines.forEach { Txt(it, Sh.style(24, color = Sh.text2), maxLines = 2) }
        Spacer(Modifier.height(28.dp))
        ShButton("preview-open", button, primary = true, icon = ShIcon.ChevronRight, height = 64, fontSize = 23) { vm.navigateFromSettings(section, route, route) }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().glass(32).padding(horizontal = 38.dp, vertical = 34.dp), content = content)
}

@Composable
private fun InfoField(label: String, value: String, modifier: Modifier = Modifier, badge: String? = null) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(Color(0x2E000000)).padding(horizontal = 26.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(label, Sh.style(20, FontWeight.Medium, Sh.hint))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Txt(value, Sh.style(24, color = Sh.text, mono = true), Modifier.weight(1f, fill = false))
            badge?.let {
                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Sh.veil(0.102f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Txt(it, Sh.style(17, color = Sh.text2))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.HaSection(vm: MainViewModel, s: AppSettings, ha: HaSnapshot) {
    var confirmDisconnect by remember { mutableStateOf(false) }
    val status = islandStatus(ha, vm.c.ha.isDemo)
    val token = remember(s.ha) { (vm.c.secrets.readToken() as? TokenResult.Present)?.token }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt("Verbindung", Sh.style(32, Sh.W650))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dot(when (status.tone) { IslandTone.Ok -> Sh.teal; IslandTone.Warn -> Sh.coral; else -> Sh.muted })
                    val sync = if (ha.lastSyncEpochMs > 0 && !vm.c.ha.isDemo) " · zuletzt synchronisiert ${formatTime(ha.lastSyncEpochMs)}" else ""
                    Txt(status.text + sync, Sh.style(22, color = Sh.text2))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (s.ha != null) ShButton("ha-test", "Verbindung testen", height = 64, fontSize = 23) { vm.reconnectHa(); vm.toast("Verbinde neu …") }
                ShButton("ha-setup", if (s.ha == null) "Einrichten" else "Neu einrichten", height = 64, fontSize = 23) { vm.navigate(Route.Setup(1)) }
            }
        }
        Spacer(Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            InfoField("Server-Adresse", s.ha?.baseUrl ?: "—", Modifier.weight(1f))
            InfoField("Zugangstoken (Long-Lived)", token?.let { maskSecret(it) } ?: "—", Modifier.weight(1f), badge = if (token != null) "maskiert" else null)
        }
        if (s.ha?.allowHttp == true) {
            Spacer(Modifier.height(16.dp))
            Txt("Unverschlüsseltes http ist für diese lokale Instanz erlaubt.", Sh.style(20, color = Sh.warnText))
        }
    }
    Card {
        Txt("Weitere Optionen", Sh.style(28, Sh.W650))
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShButton("ha-phone", "Per Handy einrichten", icon = ShIcon.Phone, height = 64, fontSize = 23) { vm.navigate(Route.Pairing) }
            if (s.ha != null) ShButton("ha-disconnect", "Trennen & Token löschen", color = Sh.dangerText, height = 64, fontSize = 23) { confirmDisconnect = true }
        }
        Spacer(Modifier.height(18.dp))
        CheckRow("demo", s.demoMode, "Demo-Modus", sub = "Beispieldaten statt echter Geräte, überall als „Demo“ gekennzeichnet") {
            vm.updateSettings { it.copy(demoMode = !it.demoMode) }
        }
        Spacer(Modifier.height(12.dp))
        Txt("Tipp: In Home Assistant einen eigenen Benutzer für Sofa Hub anlegen. Ein Token hat immer alle Rechte seines Benutzers.", Sh.style(20, color = Sh.hint), maxLines = 2)
    }
    if (confirmDisconnect) {
        ShDialog("Verbindung trennen?", "Der Token wird von diesem Fire TV gelöscht. Zuordnungen bleiben erhalten.", ShIcon.Warning, onDismiss = { confirmDisconnect = false }) {
            FocusOnShow("dc-cancel")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ShButton("dc-ok", "Trennen", primary = true) {
                    confirmDisconnect = false
                    vm.disconnectHa()
                }
                ShButton("dc-cancel", "Abbrechen") { confirmDisconnect = false }
            }
        }
    }
}

@Composable
private fun ColumnScope.AssignmentSection(vm: MainViewModel, s: AppSettings, ha: HaSnapshot) {
    // Die Kacheln der Startseite stehen jetzt unter „Startseite“; hier bleiben Wetter, Fernseher und die Listen.
    val roles = listOf(Role.WEATHER to "Wetter", Role.MEDIA_PLAYER to "Fernseher (optional)")
    Column(Modifier.fillMaxWidth().weight(1f).glass(32).padding(start = 18.dp, end = 18.dp, top = 26.dp, bottom = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
            Txt("Gerätezuordnung", Sh.style(32, Sh.W650), Modifier.weight(1f))
            Txt("OK ändert die Zuordnung", Sh.style(20, FontWeight.Medium, Sh.hint))
        }
        LazyColumn(state = de.sofahub.app.ui.components.rememberRegistryListState("assign"), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(12.dp)) {
            items(roles.size) { i ->
                val (role, label) = roles[i]
                val id = s.roles[role]
                val missing = id != null && ha.connection == HaConnection.Connected && id !in ha.entities
                AssignRow("role-${role.name}", label, id ?: "Nicht zugeordnet", missing) { vm.navigateFromSettings(SettingsSection.Assignment, Route.Picker(PickerTarget.ForRole(role))) }
            }
            item { AssignRow("windows", "Fenster & Türen", if (s.windowEntities.isEmpty()) "Keine" else "${s.windowEntities.size} zugeordnet", false) { vm.navigateFromSettings(SettingsSection.Assignment, Route.Picker(PickerTarget.Windows)) } }
            item { AssignRow("calendars", "Kalender & Müllabfuhr", if (s.calendarEntities.isEmpty()) "Keine" else "${s.calendarEntities.size} Kalender", false) { vm.navigateFromSettings(SettingsSection.Assignment, Route.Picker(PickerTarget.Calendars)) } }
            item { AssignRow("notices", "Meldungen (Statusinsel)", if (s.noticeEntities.isEmpty()) "Nur 3D-Drucker" else "${s.noticeEntities.size} Entitäten + 3D-Drucker", false) { vm.navigateFromSettings(SettingsSection.Assignment, Route.Picker(PickerTarget.Notices)) } }
            item {
                CheckRow("notices-over", s.noticesOverApps, "Meldungen auch über anderen Apps", sub = "Netflix, YouTube, Spiegeln … – braucht den Hintergrunddienst (siehe „Start & Kiosk“)") {
                    vm.updateSettings { it.copy(noticesOverApps = !it.noticesOverApps) }
                }
            }
            item { AssignRow("smarthome", "Smart-Home-Seite", if (s.smartHomeEntities.isEmpty()) "Keine Geräte" else "${s.smartHomeEntities.size} Geräte", false) { vm.navigateFromSettings(SettingsSection.Assignment, Route.Picker(PickerTarget.SmartHome)) } }
        }
    }
    if (vm.c.ha.isDemo) Txt("Im Demo-Modus werden Beispielgeräte angezeigt; diese Zuordnungen gelten für echte Geräte.", Sh.style(20, color = Sh.hint))
}

@Composable
private fun AssignRow(key: String, label: String, value: String, missing: Boolean, onClick: () -> Unit) {
    Tile(key, Modifier.fillMaxWidth().height(76.dp), radius = 22, style = TileStyle.Plain, focusScale = 1.03f, padding = PaddingValues(horizontal = 26.dp), onClick = onClick) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Txt(label, Sh.style(25, FontWeight.SemiBold), Modifier.width(320.dp))
            Txt(value, Sh.style(21, color = if (missing) Sh.coralText else Sh.text3, mono = true), Modifier.weight(1f))
            if (missing) Txt("fehlt in HA", Sh.style(20, color = Sh.coralText))
            Icon(ShIcon.ChevronRight, Sh.muted, 26)
        }
    }
}

@Composable
private fun ColumnScope.AppearanceSection(vm: MainViewModel, s: AppSettings) {
    val pickerAvailable = remember { vm.c.photos.pickerAvailable() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.addPickedPhotos(listOf(it), asBackground = true) } }
    Card {
        Txt("Farbdesign", Sh.style(32, Sh.W650))
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            de.sofahub.app.ui.theme.ColorThemes.all.forEach { t ->
                val sel = t.id == s.colorTheme
                Tile(
                    key = "theme-${t.id}",
                    modifier = Modifier.weight(1f).height(118.dp),
                    radius = 24,
                    borderColor = if (sel) t.accent else null,
                    padding = PaddingValues(16.dp),
                    onClick = { vm.updateSettings { it.copy(colorTheme = t.id) } },
                ) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(t.logoStart, t.logoEnd))))
                            Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(t.bg).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(11.dp)))
                            Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(t.accent))
                            Spacer(Modifier.weight(1f))
                            if (sel) Icon(ShIcon.Check, t.accentText, 24)
                        }
                        Txt(t.name, Sh.style(20, FontWeight.SemiBold))
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            CheckRow("mood-greeting", s.greeting, "Begrüßung & warmer Abend", Modifier.weight(1f), sub = "„Guten Abend“ oben, abends wärmer und gedämpfter") {
                vm.updateSettings { it.copy(greeting = !it.greeting) }
            }
            CheckRow("mood-weather", s.weatherMood, "Wetter im Hintergrund", Modifier.weight(1f), sub = "Regen, Schnee, Sonne oder Sterne – dezent, nur Startseite") {
                vm.updateSettings { it.copy(weatherMood = !it.weatherMood) }
            }
        }
    }
    Card {
        Txt("Hintergrundbild", Sh.style(32, Sh.W650))
        Spacer(Modifier.height(8.dp))
        Txt(
            if (s.background == null) "Standard-Hintergrund des Designs." else "Eigenes Bild aktiv – es wird abgedunkelt, damit alles lesbar bleibt.",
            Sh.style(22, color = Sh.text2),
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShButton("bg-phone", "Vom Handy übertragen", icon = ShIcon.Phone, height = 64, fontSize = 23) { vm.navigate(Route.Pairing) }
            if (pickerAvailable) ShButton("bg-pick", "Auf dem Fire TV wählen", icon = ShIcon.Image, height = 64, fontSize = 23) {
                runCatching { launcher.launch(arrayOf("image/*")) }.onFailure { vm.toast("Dateiauswahl nicht verfügbar", warn = true) }
            }
            if (s.photos.isNotEmpty()) ShButton("bg-photo", "Erstes Diashow-Foto", height = 64, fontSize = 23) {
                vm.updateSettings { it.copy(background = it.photos.first()) }
                vm.toast("Hintergrund gesetzt")
            }
            if (s.background != null) ShButton("bg-clear", "Standard", height = 64, fontSize = 23) { vm.clearBackground() }
        }
        if (!pickerAvailable) {
            Spacer(Modifier.height(14.dp))
            Txt("Dieser Fire TV bietet keine Dateiauswahl für Bilder – Bilder bitte über das Handy übertragen.", Sh.style(20, color = Sh.hint))
        }
    }
    if (s.background != null) Card {
        Txt("Abdunkelung", Sh.style(32, Sh.W650))
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ShButton("dim-minus", "Heller", icon = ShIcon.ChevronLeft, height = 64, fontSize = 23, enabled = s.backgroundDimPercent > 0) {
                vm.updateSettings { it.copy(backgroundDimPercent = (it.backgroundDimPercent - 5).coerceAtLeast(0)) }
            }
            Txt("${s.backgroundDimPercent} %", Sh.style(40, FontWeight.SemiBold), Modifier.width(140.dp))
            ShButton("dim-plus", "Dunkler", trailingIcon = ShIcon.ChevronRight, height = 64, fontSize = 23, enabled = s.backgroundDimPercent < 90) {
                vm.updateSettings { it.copy(backgroundDimPercent = (it.backgroundDimPercent + 5).coerceAtMost(90)) }
            }
        }
    }
}

@Composable
private fun ColumnScope.PhotosSection(vm: MainViewModel, s: AppSettings) {
    val pickerAvailable = remember { vm.c.photos.pickerAvailable() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.addPickedPhotos(uris, asBackground = false) }
    var note by remember(s.note) { mutableStateOf(s.note) }
    val dirty = note != s.note
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("Fotos", Sh.style(32, Sh.W650))
                Txt(if (s.photos.isEmpty()) "Noch keine Fotos." else "${s.photos.size} Fotos · Wechsel alle ${s.slideshowSeconds} s", Sh.style(22, color = Sh.text2))
            }
            if (s.photos.isNotEmpty()) ShButton("slideshow", "Diashow starten", primary = true, icon = ShIcon.Play, height = 64, fontSize = 23) { vm.navigate(Route.Slideshow) }
        }
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShButton("ph-phone", "Vom Handy übertragen", icon = ShIcon.Phone, height = 64, fontSize = 23) { vm.navigate(Route.Pairing) }
            if (pickerAvailable) ShButton("ph-pick", "Auf dem Fire TV wählen", icon = ShIcon.Image, height = 64, fontSize = 23) {
                runCatching { launcher.launch(arrayOf("image/*")) }.onFailure { vm.toast("Dateiauswahl nicht verfügbar", warn = true) }
            }
            ShButton("ph-int", "${s.slideshowSeconds} s", icon = ShIcon.Pause, height = 64, fontSize = 23) {
                vm.updateSettings { it.copy(slideshowSeconds = listOf(5, 8, 15, 30, 60).let { l -> l[(l.indexOf(it.slideshowSeconds) + 1) % l.size] }) }
            }
            if (s.photos.isNotEmpty()) ShButton("ph-clear", "Alle entfernen", color = Sh.dangerText, height = 64, fontSize = 23) {
                s.photos.forEach { vm.removePhoto(it) }
            }
        }
    }
    Card(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Persönliche Notiz", Sh.style(32, Sh.W650), Modifier.weight(1f))
            Txt("${note.length}/${AppSettings.MAX_NOTE_LENGTH}", Sh.style(20, color = if (note.length >= AppSettings.MAX_NOTE_LENGTH) Sh.coralText else Sh.hint))
        }
        Spacer(Modifier.height(16.dp))
        ShTextField("note", "Erscheint unten auf der Startseite", note, { note = it }, mono = false, singleLine = false, height = 150, maxLength = AppSettings.MAX_NOTE_LENGTH)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShButton("note-save", "Speichern", primary = true, enabled = dirty, height = 64, fontSize = 23) {
                vm.updateSettings { it.copy(note = note.take(AppSettings.MAX_NOTE_LENGTH)) }
                vm.toast("Notiz gespeichert")
            }
            ShButton("note-cancel", "Verwerfen", enabled = dirty, height = 64, fontSize = 23) { note = s.note }
            if (s.note.isNotEmpty()) ShButton("note-clear", "Leeren", height = 64, fontSize = 23) {
                note = ""
                vm.updateSettings { it.copy(note = "") }
                vm.toast("Notiz geleert")
            }
        }
    }
}

@Composable
private fun ColumnScope.KioskSection(vm: MainViewModel, s: AppSettings) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Bei jeder Rückkehr in die App neu prüfen (Dienst wird außerhalb per ADB aktiviert).
    var serviceOn by remember { mutableStateOf(de.sofahub.app.kiosk.KioskService.isEnabled(context)) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            serviceOn = de.sofahub.app.kiosk.KioskService.isEnabled(context)
            kotlinx.coroutines.delay(2000)
        }
    }
    Card {
        Txt("Kiosk-Modus", Sh.style(32, Sh.W650))
        Spacer(Modifier.height(8.dp))
        Txt("Sofa Hub startet beim Einschalten des Fire TV automatisch, und die Home-Taste führt immer zurück zu Sofa Hub. Streaming-Apps und Fire-TV-Einstellungen bleiben normal erreichbar.", Sh.style(22, color = Sh.text2, lineHeight = 1.4f), maxLines = 3)
        Spacer(Modifier.height(20.dp))
        CheckRow("kiosk", s.kioskMode, "Kiosk-Modus einschalten", sub = if (s.kioskMode) "Zurück auf der Startseite verlässt Sofa Hub nicht mehr" else "Aus: Sofa Hub verhält sich wie eine normale App") {
            vm.updateSettings { it.copy(kioskMode = !it.kioskMode) }
        }
    }
    Card(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Dot(if (serviceOn) Sh.teal else Sh.coral)
            Txt(if (serviceOn) "Hintergrunddienst ist aktiv" else "Hintergrunddienst ist noch nicht aktiviert", Sh.style(28, Sh.W650))
        }
        Spacer(Modifier.height(12.dp))
        if (serviceOn) {
            Txt("Fire OS meldet den Dienst „Sofa Hub Hintergrunddienst“ als eingeschaltet. Er wertet nur aus, welche App im Vordergrund ist, und liest keine Inhalte.", Sh.style(21, color = Sh.text2, lineHeight = 1.4f), maxLines = 3)
        } else {
            Txt("Fire OS hat kein Menü für Bedienungshilfen anderer Apps. Einmalig am PC per ADB aktivieren (siehe README):", Sh.style(21, color = Sh.text2, lineHeight = 1.4f), maxLines = 2)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x40000000)).padding(18.dp)) {
                Txt(de.sofahub.app.kiosk.KioskService.adbCommand(context), Sh.style(19, color = Sh.text, mono = true), maxLines = 3)
            }
            if (s.kioskMode) {
                Spacer(Modifier.height(10.dp))
                Txt("Der Kiosk-Modus ist eingeschaltet, wirkt aber erst mit aktivem Dienst.", Sh.style(20, color = Sh.warnText))
            }
        }
    }
}

@Composable
private fun ColumnScope.AutomationsSection(vm: MainViewModel, s: AppSettings, ha: HaSnapshot) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val installed by vm.apps.collectAsStateWithLifecycle()
    val lastRun by vm.c.automations.lastRun.collectAsStateWithLifecycle()
    var serviceOn by remember { mutableStateOf(de.sofahub.app.kiosk.KioskService.isEnabled(context)) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            serviceOn = de.sofahub.app.kiosk.KioskService.isEnabled(context)
            kotlinx.coroutines.delay(2000)
        }
    }
    // OK auf eine Aktion öffnet ihr Menü (Farbe ändern, Entfernen); Regel entfernen mit Rückfrage.
    // Gemerkt wird die Aktion selbst, nicht ihre Position – ändert sich die Liste darunter, trifft der Dialog nie eine andere.
    var editAction by remember { mutableStateOf<Pair<AutomationSlot, de.sofahub.app.core.model.HaAction>?>(null) }
    val autoRegistry = de.sofahub.app.ui.components.LocalFocusRegistry.current
    var colorEdit by remember { mutableStateOf<Pair<AutomationSlot, de.sofahub.app.core.model.HaAction>?>(null) }
    var colorFor by remember { mutableStateOf<AutomationSlot?>(null) }
    var colorLight by remember { mutableStateOf<String?>(null) }
    var removeRule by remember { mutableStateOf<String?>(null) }
    fun name(id: String) = ha.entities[id]?.friendlyName ?: id
    fun appLabel(pkg: String) = installed.firstOrNull { it.packageName == pkg }?.label ?: pkg

    Column(Modifier.fillMaxWidth().weight(1f).glass(32).padding(start = 18.dp, end = 18.dp, top = 26.dp, bottom = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt("Automationen", Sh.style(32, Sh.W650), Modifier.weight(1f))
            Dot(if (serviceOn) Sh.teal else Sh.coral)
            Txt(if (serviceOn) "Hintergrunddienst aktiv" else "Hintergrunddienst fehlt (siehe „Start & Kiosk“)", Sh.style(20, FontWeight.Medium, if (serviceOn) Sh.hint else Sh.warnText))
        }
        lastRun?.let { r ->
            Txt(
                "Zuletzt: ${r.trigger} · ${formatTime(r.epochMs)} · ${r.ok} ok" + if (r.failed > 0) ", ${r.failed} fehlgeschlagen" else "",
                Sh.style(20, color = if (r.failed > 0) Sh.warnText else Sh.hint), Modifier.padding(start = 20.dp, bottom = 6.dp),
            )
        }
        LazyColumn(state = de.sofahub.app.ui.components.rememberRegistryListState("auto"), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(12.dp)) {
            fun slotBlock(scope: androidx.compose.foundation.lazy.LazyListScope, key: String, title: String, sub: String, slot: AutomationSlot, actions: List<de.sofahub.app.core.model.HaAction>) = with(scope) {
                item(key = "h-$key") { AutoHeader(title, sub) }
                items(actions.size, key = { "$key-a$it" }) { i ->
                    val a = actions[i]
                    AutoRow("$key-a$i", name(a.entityId), AutomationRunner.describe(a), a.swatch()) { editAction = slot to a }
                }
                item(key = "b-$key") {
                    Row(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 10.dp).enterFirst(autoRegistry, "$key-add"), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ShButton("$key-add", "Aktion", icon = ShIcon.Plus, height = 58, fontSize = 22) { vm.navigateFromSettings(SettingsSection.Automations, Route.Picker(PickerTarget.ForAction(slot))) }
                        if (actions.isNotEmpty()) ShButton("$key-test", "Jetzt testen", icon = ShIcon.Play, height = 58, fontSize = 22) { vm.testAutomation(slot) }
                    }
                }
            }
            slotBlock(this, "off", "Beim Ausschalten des Fire TV", "z. B. Licht und TV-Steckdose aus", AutomationSlot.PowerOff, s.powerOffActions)
            slotBlock(this, "on", "Beim Einschalten des Fire TV", "z. B. Licht an", AutomationSlot.PowerOn, s.powerOnActions)
            item(key = "h-apps") { AutoHeader("Beim Öffnen von Apps", "Lichtszene je App – auch beim Start außerhalb von Sofa Hub") }
            s.appRules.forEach { rule ->
                val pkg = rule.packageName
                item(key = "rule-$pkg") {
                    Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        de.sofahub.app.feature.AppIcon(de.sofahub.app.feature.rememberAppArt(vm, pkg), appLabel(pkg), pkg, 46, 12)
                        Txt(appLabel(pkg), Sh.style(25, FontWeight.SemiBold), Modifier.weight(1f))
                        if (rule.actions.isEmpty()) Txt("noch keine Aktion", Sh.style(20, color = Sh.warnText))
                    }
                }
                val slot = AutomationSlot.App(pkg)
                items(rule.actions.size, key = { "rule-$pkg-a$it" }) { i ->
                    val a = rule.actions[i]
                    AutoRow("rule-$pkg-a$i", name(a.entityId), AutomationRunner.describe(a), a.swatch()) { editAction = slot to a }
                }
                item(key = "rule-$pkg-b") {
                    Row(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 10.dp).enterFirst(autoRegistry, "rule-$pkg-add"), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ShButton("rule-$pkg-add", "Aktion", icon = ShIcon.Plus, height = 58, fontSize = 22) { vm.navigateFromSettings(SettingsSection.Automations, Route.Picker(PickerTarget.ForAction(slot))) }
                        ShButton("rule-$pkg-color", "Lichtfarbe", icon = ShIcon.Bulb, height = 58, fontSize = 22) { colorFor = slot }
                        if (rule.actions.isNotEmpty()) ShButton("rule-$pkg-test", "Jetzt testen", icon = ShIcon.Play, height = 58, fontSize = 22) { vm.testAutomation(slot) }
                        ShButton("rule-$pkg-del", "Regel entfernen", icon = ShIcon.Trash, height = 58, fontSize = 22) { removeRule = pkg }
                    }
                }
            }
            item(key = "apps-add") {
                Row(Modifier.padding(start = 8.dp, top = 6.dp)) {
                    ShButton("apps-add", "App-Regel", icon = ShIcon.Plus, primary = true, height = 58, fontSize = 22) { vm.navigateFromSettings(SettingsSection.Automations, Route.Picker(PickerTarget.NewAppRule)) }
                }
            }
            item(key = "h-presence") { AutoHeader("Fire TV in Home Assistant", "Für Automationen direkt in Home Assistant") }
            item(key = "presence") { PresenceBlock(vm, s) }
        }
    }
    fun actionsOf(slot: AutomationSlot) = when (slot) {
        AutomationSlot.PowerOff -> s.powerOffActions
        AutomationSlot.PowerOn -> s.powerOnActions
        is AutomationSlot.App -> s.appRules.firstOrNull { it.packageName == slot.packageName }?.actions.orEmpty()
    }
    fun keyOf(slot: AutomationSlot) = when (slot) {
        AutomationSlot.PowerOff -> "off"
        AutomationSlot.PowerOn -> "on"
        is AutomationSlot.App -> "rule-${slot.packageName}"
    }
    editAction?.let { (slot, a) ->
        val i = actionsOf(slot).indexOf(a)
        if (i < 0) editAction = null
        else ShDialog(name(a.entityId), AutomationRunner.describe(a), ShIcon.Sliders, onDismiss = { editAction = null }) {
            val light = a.entityId.startsWith("light.") && a.kind == de.sofahub.app.core.model.ActionKind.ON
            FocusOnShow(if (light) "ea-color" else "ea-cancel")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (light) ShButton("ea-color", "Farbe & Helligkeit", icon = ShIcon.Bulb, primary = true) { editAction = null; colorEdit = slot to a }
                ShButton("ea-remove", "Entfernen", icon = ShIcon.Trash, color = Sh.dangerText) {
                    // Die Zeile verschwindet: danach auf die nächste Aktion, sonst die vorige, sonst „Aktion“.
                    val k = keyOf(slot)
                    autoRegistry.returnFocusTo(if (i < actionsOf(slot).size - 1) "$k-a$i" else if (i > 0) "$k-a${i - 1}" else "$k-add")
                    editAction = null; vm.removeAutomationAction(slot, i); vm.toast("Aktion entfernt")
                }
                ShButton("ea-cancel", "Abbrechen") { editAction = null }
            }
        }
    }
    colorEdit?.let { (slot, a) ->
        val i = actionsOf(slot).indexOf(a)
        if (i < 0) colorEdit = null
        else {
            LightColorDialog(name(a.entityId), a.entityId, a, onSave = { vm.replaceAutomationAction(slot, i, it); colorEdit = null; vm.toast("Lichtfarbe gespeichert", AutomationRunner.describe(it)) }) { colorEdit = null }
        }
    }
    // „Lichtfarbe“ bei einer App-Regel: erst das Licht wählen, dann die Farbe.
    colorFor?.let { slot ->
        // Wie bei „Aktion“: keine Demo-Geräte in echte Automationen übernehmen, ohne Verbindung keine Auswahl.
        val connected = ha.connection == de.sofahub.app.core.model.HaConnection.Connected
        val lights = if (!vm.c.ha.isDemo && connected) ha.entities.values.filter { it.domain == "light" }.sortedBy { it.friendlyName.lowercase() } else emptyList()
        val hint = when {
            vm.c.ha.isDemo -> "Im Demo-Modus gibt es keine echten Geräte. Demo-Modus in den Einstellungen ausschalten."
            !connected -> "Keine Verbindung zu Home Assistant – Lichter können erst danach gewählt werden."
            lights.isEmpty() -> "Keine Lichter in Home Assistant gefunden."
            else -> "Die Farbe wird beim Öffnen der App gesetzt."
        }
        val chosen = colorLight
        if (chosen == null) ShDialog("Welches Licht?", hint, ShIcon.Bulb, onDismiss = { colorFor = null }) {
            FocusOnShow(if (lights.isEmpty()) "cl-cancel" else "cl-0")
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lights.size) { i ->
                    val l = lights[i]
                    ShButton("cl-$i", l.friendlyName, Modifier.fillMaxWidth(), icon = ShIcon.Bulb, height = 58, fontSize = 21) { colorLight = l.entityId }
                }
            }
            ShButton("cl-cancel", "Abbrechen") { colorFor = null }
        } else {
            LightColorDialog(name(chosen), chosen, null, onSave = {
                vm.addAutomationAction(slot, it)
                vm.toast("Lichtfarbe hinzugefügt", "${name(chosen)} · ${AutomationRunner.describe(it)}")
                colorFor = null; colorLight = null
            }) { colorFor = null; colorLight = null }
        }
    }
    removeRule?.let { pkg ->
        ShDialog("Regel für ${appLabel(pkg)} entfernen?", "Alle Aktionen dieser App-Regel werden gelöscht.", ShIcon.Warning, onDismiss = { removeRule = null }) {
            FocusOnShow("rr-cancel")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ShButton("rr-ok", "Entfernen", primary = true) { autoRegistry.returnFocusTo("apps-add"); removeRule = null; vm.removeAppRule(pkg) }
                ShButton("rr-cancel", "Abbrechen") { removeRule = null }
            }
        }
    }
}

@Composable
private fun AutoHeader(title: String, sub: String) {
    Column(Modifier.padding(start = 8.dp, top = 14.dp, bottom = 8.dp)) {
        Txt(title, Sh.style(26, Sh.W650))
        Txt(sub, Sh.style(19, color = Sh.hint))
    }
}

/**
 * Eine Aktion als ganze, fokussierbare Zeile (OK öffnet ihr Menü). Alle Zeilen und Knopfreihen beginnen links bündig –
 * so führt ▲ ▼ verlässlich von Zeile zu Zeile, ohne dass Knöpfe nur „über Umwege“ erreichbar sind.
 */
@Composable
private fun AutoRow(key: String, label: String, value: String, swatch: Color?, onClick: () -> Unit) {
    Tile(key, Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(68.dp), radius = 20, style = TileStyle.Plain, background = androidx.compose.ui.graphics.Brush.linearGradient(List(2) { Sh.veil(0.078f) }), focusScale = 1.02f, padding = PaddingValues(horizontal = 24.dp), onClick = onClick) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (swatch != null) Box(Modifier.size(26.dp).clip(CircleShape).background(swatch))
            Txt(label, Sh.style(23, FontWeight.SemiBold), Modifier.width(380.dp), maxLines = 1)
            Txt(value, Sh.style(20, color = Sh.text3), Modifier.weight(1f), maxLines = 1)
            Icon(ShIcon.ChevronRight, Sh.muted, 24)
        }
    }
}

@Composable
private fun ColumnScope.InfoSection(vm: MainViewModel, s: AppSettings, ha: HaSnapshot) {
    var confirmReset by remember { mutableStateOf(false) }
    val capability by vm.miracast.collectAsStateWithLifecycle()
    val installAllowed = remember { runCatching { vm.getApplication<android.app.Application>().packageManager.canRequestPackageInstalls() }.getOrDefault(false) }
    val conn = ha.connection
    val haCode = when (conn) {
        is HaConnection.Retrying -> conn.error.code()
        HaConnection.Connected -> "OK"
        else -> conn.javaClass.simpleName.uppercase()
    }
    Card(Modifier.weight(1f)) {
        Txt("Diagnose", Sh.style(32, Sh.W650))
        Spacer(Modifier.height(18.dp))
        val rows = listOf(
            "App" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.APPLICATION_ID}${if (BuildConfig.DEBUG) " · Debug" else ""}",
            "Gerät" to "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}",
            "Home Assistant" to "$haCode · ${ha.entities.size} Entitäten · Räume ${if (ha.registryAvailable) "aus Registry" else "manuell/keine"}",
            "Spiegeln" to capability.name,
            "Installationsfreigabe" to if (installAllowed) "erteilt" else "nicht erteilt",
            "Update-Quelle" to "github.com/${BuildConfig.UPDATE_REPO}",
            "Daten" to "${s.favorites.size} Favoriten · ${s.photos.size} Fotos · Schema ${s.schemaVersion}",
        )
        rows.forEach { (k, v) ->
            Row(Modifier.padding(vertical = 8.dp)) {
                Txt(k, Sh.style(22, FontWeight.Medium, Sh.hint), Modifier.width(300.dp))
                Txt(v, Sh.style(21, color = Sh.text, mono = true), Modifier.weight(1f), maxLines = 2)
            }
        }
        Spacer(Modifier.weight(1f))
        Txt("Sicherung: öffnet die Einrichtungsseite – dort im PC-Browser „Sicherung herunterladen“ oder wiederherstellen.", Sh.style(20, color = Sh.hint), maxLines = 2)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShButton("backup", "Sicherung …", icon = ShIcon.Download, height = 64, fontSize = 23) { vm.navigateFromSettings(SettingsSection.Info, Route.Pairing) }
            ShButton("reset", "App zurücksetzen", color = Sh.dangerText, height = 64, fontSize = 23) { confirmReset = true }
            if (BuildConfig.DEBUG) ShButton("installer-test", "Installer-Test (Debug)", height = 64, fontSize = 23) {
                val f = java.io.File(vm.getApplication<android.app.Application>().filesDir, "installer-test.apk")
                if (!f.exists()) vm.toast("files/installer-test.apk fehlt", warn = true)
                else { vm.c.updates.installLocalTestApk(f); vm.navigate(Route.Updates) }
            }
        }
    }
    if (confirmReset) {
        ShDialog("Sofa Hub zurücksetzen?", "Alle Einstellungen, der Token, Favoriten, Fotos und das Hintergrundbild werden von diesem Fire TV gelöscht. Vorher eine Sicherung herunterladen, um sie später wiederherzustellen.", ShIcon.Warning, onDismiss = { confirmReset = false }) {
            FocusOnShow("reset-cancel")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ShButton("reset-ok", "Zurücksetzen", primary = true) { confirmReset = false; vm.resetApp() }
                ShButton("reset-cancel", "Abbrechen") { confirmReset = false }
            }
        }
    }
}

/** Meldung des Fire-TV-Zustands an Home Assistant: Schalter, Status und welche Entitäten entstehen. */
@Composable
private fun PresenceBlock(vm: MainViewModel, s: AppSettings) {
    val status by vm.c.presence.status.collectAsStateWithLifecycle()
    Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CheckRow("presence", s.reportToHa, "Zustand an Home Assistant melden", sub = "${de.sofahub.app.integration.homeassistant.HaPresenceReporter.TV_ENTITY} (an/aus) · ${de.sofahub.app.integration.homeassistant.HaPresenceReporter.APP_ENTITY} (laufende App)") {
            vm.updateSettings { it.copy(reportToHa = !it.reportToHa) }
        }
        val (text, color) = when (val st = status) {
            de.sofahub.app.integration.homeassistant.PresenceStatus.Off -> (if (s.ha == null) "Home Assistant ist nicht eingerichtet." else "Ausgeschaltet.") to Sh.hint
            de.sofahub.app.integration.homeassistant.PresenceStatus.Idle -> "Wird beim nächsten Wechsel gemeldet (braucht den Hintergrunddienst)." to Sh.hint
            is de.sofahub.app.integration.homeassistant.PresenceStatus.Ok -> "Zuletzt gemeldet ${formatTime(st.epochMs)}: ${if (st.screenOn) "an · ${st.app}" else "aus"}" to Sh.hint
            is de.sofahub.app.integration.homeassistant.PresenceStatus.Failed -> when (st.error) {
                de.sofahub.app.core.model.HaError.Unauthorized, de.sofahub.app.core.model.HaError.Forbidden ->
                    "Home Assistant lehnt das Melden ab – der Token braucht Administratorrechte." to Sh.warnText
                else -> "Melden fehlgeschlagen: ${st.error.message()}" to Sh.warnText
            }
        }
        Txt(text, Sh.style(20, color = color), maxLines = 2)
        Txt("Beispiel-Automation in HA: Auslöser „Zustand von sensor.sofa_hub_app wird Netflix“. Nach einem HA-Neustart sind die Entitäten nach spätestens 10 Minuten wieder da.", Sh.style(19, color = Sh.hint), maxLines = 2)
    }
}

/** Startseite: freie Kacheln (Reihenfolge, Name, Gerät), Szenen-Leiste und welche Bereiche erscheinen. */
@Composable
private fun ColumnScope.HomeLayoutSection(vm: MainViewModel, s: AppSettings, ha: HaSnapshot, onRename: (Int) -> Unit, onWifi: () -> Unit) {
    val tiles = s.effectiveHomeTiles()
    val layout = s.homeLayout
    val demo = vm.c.ha.isDemo
    fun moveTile(i: Int, d: Int) = vm.editHomeTiles { l -> val j = i + d; if (j in l.indices) l[i] = l[j].also { l[j] = l[i] } }
    fun setLayout(change: (de.sofahub.app.core.model.HomeLayout) -> de.sofahub.app.core.model.HomeLayout) = vm.updateSettings { it.copy(homeLayout = change(it.homeLayout)) }
    fun moveBlock(b: de.sofahub.app.core.model.HomeBlock, d: Int) = setLayout { l ->
        val order = l.blocks.toMutableList()
        val i = order.indexOf(b)
        val j = i + d
        if (j in order.indices) order[i] = order[j].also { order[j] = order[i] }
        l.copy(order = order)
    }
    Column(Modifier.fillMaxWidth().weight(1f).glass(32).padding(start = 18.dp, end = 18.dp, top = 26.dp, bottom = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Txt("Startseite", Sh.style(32, Sh.W650), Modifier.weight(1f))
            Txt("Auf der Startseite: OK lang auf eine Kachel öffnet ihr Menü", Sh.style(20, FontWeight.Medium, Sh.hint))
        }
        if (demo) Txt("Im Demo-Modus zeigt die Startseite Beispielkacheln; hier gelten die Einstellungen für echte Geräte.", Sh.style(20, color = Sh.hint), Modifier.padding(start = 20.dp, bottom = 6.dp))
        LazyColumn(state = de.sofahub.app.ui.components.rememberRegistryListState("home"), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(12.dp)) {
            item(key = "h-tiles") { AutoHeader("Kacheln · ${tiles.size} von ${AppSettings.MAX_HOME_TILES}", "Jedes Gerät, jede Szene, jeder Sensor – in deiner Reihenfolge") }
            items(tiles.size, key = { "t$it-${tiles[it].entityId}" }) { i ->
                val t = tiles[i]
                val missing = ha.connection == HaConnection.Connected && t.entityId !in ha.entities
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Txt(t.title(ha), Sh.style(25, FontWeight.SemiBold), maxLines = 1)
                        Txt(t.entityId + if (missing) " · fehlt in HA" else "", Sh.style(19, color = if (missing) Sh.coralText else Sh.text3, mono = true), maxLines = 1)
                    }
                    de.sofahub.app.feature.dashboard.IconTileButton("t$i-up", ShIcon.ChevronUp, enabled = i > 0) { moveTile(i, -1) }
                    de.sofahub.app.feature.dashboard.IconTileButton("t$i-down", ShIcon.ChevronDown, enabled = i < tiles.lastIndex) { moveTile(i, 1) }
                    ShButton("t$i-name", "Name", icon = ShIcon.Edit, height = 60, fontSize = 21) { onRename(i) }
                    ShButton("t$i-dev", "Gerät", icon = ShIcon.Assign, height = 60, fontSize = 21) { vm.navigateFromSettings(SettingsSection.Home, Route.Picker(PickerTarget.ForTile(i))) }
                    ShButton("t$i-del", "Entfernen", height = 60, fontSize = 21, color = Sh.dangerText) { vm.editHomeTiles { it.removeAt(i) }; vm.toast("Kachel entfernt") }
                }
            }
            item(key = "t-add") {
                Row(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 10.dp)) {
                    ShButton("t-add", "Kachel hinzufügen", icon = ShIcon.Plus, primary = true, height = 58, fontSize = 22, enabled = tiles.size < AppSettings.MAX_HOME_TILES) {
                        vm.navigateFromSettings(SettingsSection.Home, Route.Picker(PickerTarget.ForTile(null)))
                    }
                }
            }
            item(key = "h-wifi") { AutoHeader("Gäste-WLAN", "QR-Code als Kachel – Gäste scannen ihn mit der Handykamera") }
            item(key = "wifi") {
                val w = s.guestWifi
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, bottom = 10.dp).enterFirst(de.sofahub.app.ui.components.LocalFocusRegistry.current, "wifi-edit"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ShButton("wifi-edit", if (w == null) "Einrichten" else "Ändern", icon = if (w == null) ShIcon.Plus else ShIcon.Edit, primary = w == null, height = 58, fontSize = 22) { onWifi() }
                    ShButton("wifi-phone", "Per Handy/PC eingeben", icon = ShIcon.Phone, height = 58, fontSize = 22) { vm.navigateFromSettings(SettingsSection.Home, Route.Pairing) }
                    if (w != null) ShButton("wifi-del", "Entfernen", color = Sh.dangerText, height = 58, fontSize = 22) {
                        vm.updateSettings { it.copy(guestWifi = null) }
                        vm.toast("Gäste-WLAN entfernt")
                    }
                    Txt(w?.let { "${it.ssid} · ${it.security.label}" } ?: "Nicht eingerichtet", Sh.style(21, color = Sh.text2), Modifier.weight(1f), maxLines = 1)
                }
            }
            item(key = "h-areas") { AutoHeader("Bereiche", "Ausblenden oder Reihenfolge ändern") }
            item(key = "a-mirror") {
                CheckRow("a-mirror", layout.showMirror, "Bildschirm spiegeln", sub = "Große Kachel oben links; ausgeblendet bekommen die Kacheln mehr Platz") { setLayout { it.copy(showMirror = !it.showMirror) } }
            }
            val blocks = layout.blocks
            items(blocks.size, key = { "b-${blocks[it]}" }) { i ->
                val b = blocks[i]
                val (label, sub) = when (b) {
                    de.sofahub.app.core.model.HomeBlock.SCENES -> "Szenen-Leiste" to (if (s.sceneBar.isEmpty()) "Noch keine Szenen gewählt" else "${s.sceneBar.size} Szenen")
                    de.sofahub.app.core.model.HomeBlock.APPS -> "Apps (Favoriten / Zuletzt genutzt)" to "${s.favorites.size} Favoriten"
                    de.sofahub.app.core.model.HomeBlock.HOUSE -> "Hausübersicht" to "Räume, Fenster, Geräte"
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) {
                        CheckRow("b-$b", layout.shows(b), label, sub = sub) {
                            setLayout { l ->
                                when (b) {
                                    de.sofahub.app.core.model.HomeBlock.SCENES -> l.copy(showScenes = !l.showScenes)
                                    de.sofahub.app.core.model.HomeBlock.APPS -> l.copy(showApps = !l.showApps)
                                    de.sofahub.app.core.model.HomeBlock.HOUSE -> l.copy(showHouse = !l.showHouse)
                                }
                            }
                        }
                    }
                    if (b == de.sofahub.app.core.model.HomeBlock.SCENES) ShButton("b-scenes-pick", "Szenen", icon = ShIcon.Film, height = 60, fontSize = 21) {
                        vm.navigateFromSettings(SettingsSection.Home, Route.Picker(PickerTarget.SceneBar))
                    }
                    de.sofahub.app.feature.dashboard.IconTileButton("b-$b-up", ShIcon.ChevronUp, enabled = i > 0) { moveBlock(b, -1) }
                    de.sofahub.app.feature.dashboard.IconTileButton("b-$b-down", ShIcon.ChevronDown, enabled = i < blocks.lastIndex) { moveBlock(b, 1) }
                }
            }
        }
    }
}
