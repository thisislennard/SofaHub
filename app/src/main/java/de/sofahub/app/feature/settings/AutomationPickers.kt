package de.sofahub.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sofahub.app.core.model.ActionKind
import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.HaAction
import de.sofahub.app.core.model.HaConnection
import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.feature.AppIcon
import de.sofahub.app.feature.AutomationSlot
import de.sofahub.app.feature.MainViewModel
import de.sofahub.app.feature.rememberAppArt
import de.sofahub.app.feature.dashboard.Bindings
import de.sofahub.app.feature.dashboard.DashboardModel
import de.sofahub.app.integration.automation.AutomationRunner
import de.sofahub.app.integration.homeassistant.EntityView
import de.sofahub.app.ui.components.CheckRow
import de.sofahub.app.ui.components.FocusOnEnter
import de.sofahub.app.ui.components.FocusOnShow
import de.sofahub.app.ui.components.Footer
import de.sofahub.app.ui.components.PageHeader
import de.sofahub.app.ui.components.ShButton
import de.sofahub.app.ui.components.ShDialog
import de.sofahub.app.ui.components.ShTextField
import de.sofahub.app.ui.components.Tile
import de.sofahub.app.ui.components.TileStyle
import de.sofahub.app.ui.components.Txt
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon

private val ACTION_DOMAINS = AutomationRunner.SWITCHABLE_DOMAINS + AutomationRunner.ACTIVATABLE_DOMAINS

/** Szenen/Skripte zuerst – darum geht es bei App-Regeln meistens. */
private fun domainOrder(e: HaEntity) = when (e.domain) {
    "scene" -> 0
    "script" -> 1
    "light" -> 2
    else -> 3
}

/** HA-Entität für eine Automations-Aktion wählen; Lichter/Schalter fragen danach „Einschalten“ oder „Ausschalten“. */
@Composable
fun ActionPickerScreen(vm: MainViewModel, s: AppSettings, ha: HaSnapshot, slot: AutomationSlot) {
    var query by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<HaEntity?>(null) }
    var coloring by remember { mutableStateOf<HaEntity?>(null) }
    // Sortieren nur, wenn Geräte hinzukommen oder wegfallen – nicht bei jeder Sensoränderung im Haus.
    val candidateIds = remember(ha.entities.keys) {
        ha.entities.values.filter { it.domain in ACTION_DOMAINS }
            .sortedWith(compareBy<HaEntity> { domainOrder(it) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.friendlyName })
            .map { it.entityId }
    }
    val candidates = candidateIds.mapNotNull { ha.entities[it] }
        .filter { query.isBlank() || it.friendlyName.contains(query, true) || it.entityId.contains(query, true) }
    val bindings = Bindings.of(s, false)

    fun add(e: HaEntity, kind: ActionKind) {
        vm.addAutomationAction(slot, HaAction(e.entityId, kind))
        vm.toast("Aktion hinzugefügt", "${e.friendlyName} ${AutomationRunner.label(kind)}")
        vm.back()
    }

    FocusOnEnter("a0", "search")
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 54.dp)) {
        PageHeader("Aktion hinzufügen", onBack = { vm.back() })
        Spacer(Modifier.height(30.dp))
        when {
            vm.c.ha.isDemo -> Txt("Im Demo-Modus gibt es keine echten Geräte. Demo-Modus in den Einstellungen ausschalten.", Sh.style(26, color = Sh.text2))
            ha.connection != HaConnection.Connected -> Txt("Keine Verbindung zu Home Assistant – Geräte können erst nach erfolgreicher Verbindung gewählt werden.", Sh.style(26, color = Sh.text2), maxLines = 2)
            else -> {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ShTextField("search", "Suchen", query, { query = it }, Modifier.width(620.dp), placeholder = "Name oder entity_id", height = 68, mono = false)
                    Txt("Szenen, Skripte, Lichter, Schalter, Ventilatoren, Mediaplayer · ${candidates.size}", Sh.style(21, color = Sh.hint), Modifier.padding(bottom = 22.dp), maxLines = 2)
                }
                Spacer(Modifier.height(24.dp))
                if (candidates.isEmpty()) androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    de.sofahub.app.ui.components.EmptyArt(de.sofahub.app.ui.components.Illustration.Search, 96)
                    Txt("Keine passenden Entitäten gefunden.", Sh.style(24, color = Sh.text2))
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    items(candidates.size, key = { candidates[it].entityId }) { i ->
                        val e = candidates[i]
                        val room = DashboardModel.roomOf(e.entityId, bindings, ha)
                        CheckRow("a$i", false, e.friendlyName, sub = listOfNotNull(room, e.entityId, EntityView.stateLabel(e)).joinToString(" · ")) {
                            if (e.domain in AutomationRunner.ACTIVATABLE_DOMAINS) add(e, ActionKind.ACTIVATE) else pending = e
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Footer(listOf("OK" to "Wählen", "↩" to "Abbrechen"))
    }
    pending?.let { e ->
        ShDialog(e.friendlyName, "Was soll passieren?", ShIcon.Bulb, onDismiss = { pending = null }) {
            FocusOnShow("kind-on")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ShButton("kind-on", "Einschalten", primary = true) { pending = null; add(e, ActionKind.ON) }
                if (e.domain == "light") ShButton("kind-color", "Mit Farbe einschalten", icon = ShIcon.Bulb) { pending = null; coloring = e }
                ShButton("kind-off", "Ausschalten") { pending = null; add(e, ActionKind.OFF) }
            }
        }
    }
    coloring?.let { e ->
        LightColorDialog(e.friendlyName, e.entityId, null, onSave = { a ->
            coloring = null
            vm.addAutomationAction(slot, a)
            vm.toast("Aktion hinzugefügt", "${e.friendlyName} ${AutomationRunner.describe(a)}")
            vm.back()
        }) { coloring = null }
    }
}

/** Installierte App wählen, für die eine neue App-Regel angelegt wird. */
@Composable
fun AppRulePickerScreen(vm: MainViewModel, s: AppSettings) {
    val installed by vm.apps.collectAsStateWithLifecycle()
    val available = installed.filter { app -> s.appRules.none { it.packageName == app.packageName } }
    FocusOnEnter("r0")
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 54.dp)) {
        PageHeader("App für neue Regel wählen", onBack = { vm.back() })
        Spacer(Modifier.height(30.dp))
        if (installed.isEmpty()) Txt("Installierte Apps werden gesucht …", Sh.style(24, color = Sh.text2))
        else if (available.isEmpty()) Txt("Für alle Apps gibt es schon eine Regel.", Sh.style(24, color = Sh.text2))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(12.dp),
        ) {
            items(available.size, key = { available[it].packageName }) { i ->
                val app = available[i]
                Tile(
                    "r$i", Modifier.fillMaxWidth().height(96.dp), radius = 22, style = TileStyle.Plain, focusScale = 1.03f,
                    padding = PaddingValues(horizontal = 22.dp),
                    onClick = {
                        vm.addAppRule(app.packageName)
                        vm.toast("Regel angelegt", "Jetzt eine Aktion für ${app.label} hinzufügen")
                        vm.back()
                    },
                ) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        AppIcon(rememberAppArt(vm, app.packageName), app.label, app.packageName, 52, 14)
                        Column(Modifier.weight(1f)) {
                            Txt(app.label, Sh.style(24, FontWeight.SemiBold))
                            Txt(app.packageName, Sh.style(18, color = Sh.hint, mono = true))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Footer(listOf("OK" to "Regel anlegen", "↩" to "Abbrechen"))
    }
}
