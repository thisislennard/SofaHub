package de.sofahub.app.feature.settings

import de.sofahub.app.core.model.effectiveHomeTiles
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
import androidx.compose.ui.unit.dp
import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.HaConnection
import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.core.model.Role
import de.sofahub.app.feature.MainViewModel
import de.sofahub.app.feature.PickerTarget
import de.sofahub.app.feature.dashboard.Bindings
import de.sofahub.app.feature.dashboard.DashboardModel
import de.sofahub.app.integration.homeassistant.EntityView
import de.sofahub.app.ui.components.CheckRow
import de.sofahub.app.ui.components.FocusOnEnter
import de.sofahub.app.ui.components.Footer
import de.sofahub.app.ui.components.PageHeader
import de.sofahub.app.ui.components.ShButton
import de.sofahub.app.ui.components.ShTextField
import de.sofahub.app.ui.components.Txt
import de.sofahub.app.ui.theme.Sh

private fun Role.title() = when (this) {
    Role.LIGHT -> "Wohnzimmerlicht wählen"
    Role.TV_PLUG -> "TV-Steckdose wählen"
    Role.TEMPERATURE -> "Raumtemperatur wählen"
    Role.MOVIE_SCENE -> "Szene/Skript für „Filmabend“"
    Role.WEATHER -> "Wetterquelle wählen"
    Role.MEDIA_PLAYER -> "Fernseher (media_player) wählen"
}

/** Auswahl echter Entitäten aus HA, gefiltert nach passender Domain/Geräteklasse. Nichts wird vorausgesetzt oder erfunden. */
@Composable
fun PickerScreen(vm: MainViewModel, s: AppSettings, ha: HaSnapshot, target: PickerTarget) {
    when (target) {
        is PickerTarget.ForAction -> ActionPickerScreen(vm, s, ha, target.slot)
        PickerTarget.NewAppRule -> AppRulePickerScreen(vm, s)
        else -> EntityPickerScreen(vm, s, ha, target)
    }
}

private val byName = compareBy<HaEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.friendlyName }

private val NOTICE_DOMAINS = setOf("sensor", "binary_sensor", "input_boolean", "input_select", "switch", "vacuum", "lock", "cover", "alarm_control_panel")

@Composable
private fun EntityPickerScreen(vm: MainViewModel, s: AppSettings, ha: HaSnapshot, target: PickerTarget) {
    var query by remember { mutableStateOf("") }
    // Filtern und Sortieren aller Entitäten nur, wenn Geräte hinzukommen oder wegfallen – nicht bei jeder
    // Sensoränderung im Haus (bis zu 4× pro Sekunde). Die Zeilen zeigen trotzdem den aktuellen Stand.
    val candidateIds = remember(ha.entities.keys, target) {
        val all = ha.entities.values
        when (target) {
            is PickerTarget.ForRole -> DashboardModel.candidatesFor(target.role, all)
            PickerTarget.Windows -> DashboardModel.windowCandidates(all)
            PickerTarget.Calendars -> all.filter { it.domain == "calendar" }.sortedWith(byName)
            PickerTarget.Notices -> all.filter { it.domain in NOTICE_DOMAINS }.sortedWith(byName)
            is PickerTarget.ForTile -> DashboardModel.smartHomeCandidates(all.filter { it.domain in de.sofahub.app.feature.dashboard.TILE_DOMAINS }) +
                all.filter { it.domain in setOf("cover", "lock", "vacuum") }.sortedWith(byName)
            PickerTarget.SceneBar -> all.filter { it.domain == "scene" || it.domain == "script" }.sortedWith(byName)
            else -> DashboardModel.smartHomeCandidates(all)
        }.map { it.entityId }
    }
    val candidates = candidateIds.mapNotNull { ha.entities[it] }
        .filter { query.isBlank() || it.friendlyName.contains(query, true) || it.entityId.contains(query, true) }
    val multi = target !is PickerTarget.ForRole && target !is PickerTarget.ForTile
    val tiles = s.effectiveHomeTiles()
    var selected by remember {
        mutableStateOf(
            when (target) {
                is PickerTarget.ForRole -> setOfNotNull(s.roles[target.role])
                PickerTarget.Windows -> s.windowEntities.toSet()
                PickerTarget.Calendars -> s.calendarEntities.toSet()
                PickerTarget.Notices -> s.noticeEntities.toSet()
                is PickerTarget.ForTile -> setOfNotNull(target.index?.let { tiles.getOrNull(it)?.entityId })
                PickerTarget.SceneBar -> s.sceneBar.toSet()
                else -> s.smartHomeEntities.toSet()
            },
        )
    }
    val title = when (target) {
        is PickerTarget.ForRole -> target.role.title()
        PickerTarget.Windows -> "Fenster & Türen wählen"
        PickerTarget.Calendars -> "Kalender für die Startseite"
        PickerTarget.Notices -> "Meldungen in der Statusinsel"
        is PickerTarget.ForTile -> if (target.index == null) "Kachel hinzufügen" else "Gerät für die Kachel wählen"
        PickerTarget.SceneBar -> "Szenen für die Startseite"
        else -> "Geräte für Smart Home"
    }
    val bindings = Bindings.of(s, false)

    fun save() {
        vm.updateSettings {
            when (target) {
                is PickerTarget.ForRole -> it.copy(roles = selected.firstOrNull()?.let { id -> it.roles + (target.role to id) } ?: (it.roles - target.role))
                // Reihenfolge der bisherigen Auswahl bleibt erhalten, Neues wird hinten angehängt.
                PickerTarget.Windows -> it.copy(windowEntities = it.windowEntities.filter { id -> id in selected } + (selected - it.windowEntities.toSet()))
                PickerTarget.Calendars -> it.copy(calendarEntities = it.calendarEntities.filter { id -> id in selected } + (selected - it.calendarEntities.toSet()))
                PickerTarget.Notices -> it.copy(noticeEntities = it.noticeEntities.filter { id -> id in selected } + (selected - it.noticeEntities.toSet()))
                is PickerTarget.ForTile -> {
                    val id = selected.firstOrNull()
                    val list = it.effectiveHomeTiles().toMutableList()
                    when {
                        id == null -> Unit
                        target.index == null -> list += de.sofahub.app.core.model.HomeTile(id)
                        target.index in list.indices -> list[target.index] = de.sofahub.app.core.model.HomeTile(id)
                    }
                    it.copy(homeTiles = list.take(AppSettings.MAX_HOME_TILES))
                }
                PickerTarget.SceneBar -> it.copy(sceneBar = (it.sceneBar.filter { id -> id in selected } + (selected - it.sceneBar.toSet())).take(AppSettings.MAX_SCENE_BAR))
                else -> it.copy(smartHomeEntities = it.smartHomeEntities.filter { id -> id in selected } + (selected - it.smartHomeEntities.toSet()))
            }
        }
    }

    FocusOnEnter("e0", "search")
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 54.dp)) {
        PageHeader(title, onBack = { vm.back() }) {
            if (multi) ShButton("save", "Übernehmen (${selected.size})", primary = true) { save(); vm.toast("Auswahl gespeichert"); vm.back() }
            else if (target !is PickerTarget.ForTile) ShButton("none", "Keine Zuordnung") {
                selected = emptySet(); save(); vm.toast("Zuordnung entfernt"); vm.back()
            }
        }
        Spacer(Modifier.height(30.dp))
        when {
            vm.c.ha.isDemo -> Txt("Im Demo-Modus gibt es keine echten Geräte. Demo-Modus in den Einstellungen ausschalten.", Sh.style(26, color = Sh.text2))
            ha.connection != HaConnection.Connected -> Txt("Keine Verbindung zu Home Assistant – Geräte können erst nach erfolgreicher Verbindung gewählt werden.", Sh.style(26, color = Sh.text2), maxLines = 2)
            else -> {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ShTextField("search", "Suchen", query, { query = it }, Modifier.width(620.dp), placeholder = "Name oder entity_id", height = 68, mono = false)
                    Txt(
                        when (target) {
                            PickerTarget.Windows -> "Fenster-/Türsensoren (device_class) stehen oben. „An“ bedeutet bei zugeordneten Sensoren „offen“."
                            is PickerTarget.ForTile -> "Lichter, Schalter, Szenen, Sensoren, Heizung, Mediaplayer … · ${candidates.size} Entitäten"
                            PickerTarget.SceneBar -> "Bis zu ${AppSettings.MAX_SCENE_BAR} Szenen oder Skripte als Knöpfe auf der Startseite · ${selected.size} ausgewählt"
                            PickerTarget.Notices -> "Jeder Zustandswechsel erscheint kurz oben – am besten Status-Sensoren (z. B. Waschmaschine), keine Messwerte. 3D-Drucker melden sich automatisch."
                            PickerTarget.Calendars -> "Termine von heute und morgen erscheinen unten auf der Startseite – auch Müllkalender (z. B. Waste Collection Schedule)."
                            is PickerTarget.ForRole -> "${candidates.size} passende Entitäten"
                            else -> "${candidates.size} Entitäten · ${selected.size} ausgewählt"
                        },
                        Sh.style(21, color = Sh.hint), Modifier.padding(bottom = 22.dp), maxLines = 2,
                    )
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
                        val e: HaEntity = candidates[i]
                        val room = DashboardModel.roomOf(e.entityId, bindings, ha)
                        CheckRow(
                            "e$i", e.entityId in selected, e.friendlyName,
                            sub = listOfNotNull(room, e.entityId, EntityView.stateLabel(e)).joinToString(" · "),
                        ) {
                            if (multi) {
                                selected = if (e.entityId in selected) selected - e.entityId else selected + e.entityId
                            } else {
                                selected = setOf(e.entityId)
                                save()
                                vm.toast(if (target is PickerTarget.ForTile) "Kachel gespeichert" else "Zugeordnet", e.friendlyName)
                                vm.back()
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Footer(listOf("OK" to if (multi) "Auswählen" else "Zuordnen", "↩" to "Abbrechen"))
    }
}
