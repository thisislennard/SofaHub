package de.sofahub.app.feature.dashboard

import de.sofahub.app.core.model.ActionStatus
import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.Availability
import de.sofahub.app.core.model.HaConnection
import de.sofahub.app.core.model.HaEntity
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.core.model.Role
import de.sofahub.app.integration.homeassistant.DemoHomeAssistantRepository
import de.sofahub.app.integration.homeassistant.EntityView

/** Wie eine Rollen-Kachel aussehen soll – reine Ableitung aus Einstellungen und HA-Zustand (testbar, ohne UI). */
sealed interface RoleTile {
    /** Rolle nicht belegt: OK öffnet die Zuordnung. */
    /** HA fehlt/getrennt: Kachel pausiert, nichts wird erfunden. */
    data class Paused(val lastKnown: HaEntity?) : RoleTile
    /** Zugeordnete Entität existiert nicht mehr in HA. */
    data class Missing(val entityId: String) : RoleTile
    data class Ready(val entity: HaEntity, val action: ActionStatus?, val stale: Boolean) : RoleTile
}

data class HouseSummary(
    val roomNames: List<String>,
    val openWindows: List<String>,
    val windowCount: Int,
    val deviceCount: Int,
    val unavailableCount: Int,
    val available: Boolean,
)

/** Effektive Zuordnungen: im Demo-Modus immer die Beispiel-Entitäten. */
data class Bindings(
    val roles: Map<Role, String>,
    val windows: List<String>,
    val smartHome: List<String>,
    val manualRooms: Map<String, String>,
) {
    companion object {
        fun of(settings: AppSettings, demo: Boolean) = if (demo) Bindings(
            DemoHomeAssistantRepository.demoRoles, DemoHomeAssistantRepository.demoWindows, DemoHomeAssistantRepository.demoSmartHome, emptyMap(),
        ) else Bindings(settings.roles, settings.windowEntities, settings.smartHomeEntities, settings.manualRooms)
    }
}

object DashboardModel {
    fun haUsable(snap: HaSnapshot) = snap.connection == HaConnection.Connected

    /** Zustand einer frei gewählten Kachel (gleiche Zustände wie bei den Rollen-Kacheln). */
    fun entityTile(entityId: String, snap: HaSnapshot): RoleTile {
        val entity = snap.entities[entityId]
        if (!haUsable(snap)) return RoleTile.Paused(entity)
        if (entity == null) return RoleTile.Missing(entityId)
        return RoleTile.Ready(entity, snap.actions[entityId], snap.stale)
    }

    /** Raumname einer Entität: manuelle Zuordnung vor HA-Registry. */
    fun roomOf(entityId: String, bindings: Bindings, snap: HaSnapshot): String? =
        bindings.manualRooms[entityId] ?: snap.entityAreas[entityId]?.let { snap.areas[it]?.name }

    fun house(bindings: Bindings, snap: HaSnapshot): HouseSummary {
        val ids = (bindings.smartHome + bindings.roles.values + bindings.windows).distinct()
            .filter { !it.startsWith("scene.") && !it.startsWith("script.") && !it.startsWith("weather.") }
        val entities = ids.mapNotNull { snap.entities[it] }
        val windows = bindings.windows.mapNotNull { snap.entities[it] }
        // Fenster: ausdrücklich zugeordnet -> "on" heißt offen (passende device_class wird in der Auswahl vorgeschlagen).
        val open = windows.filter { it.state == "on" }
        val rooms = ids.mapNotNull { roomOf(it, bindings, snap) }.distinct()
        return HouseSummary(
            roomNames = rooms,
            openWindows = open.map { w -> roomOf(w.entityId, bindings, snap) ?: w.friendlyName }.distinct(),
            windowCount = windows.size,
            deviceCount = entities.size,
            unavailableCount = entities.count { it.availability == Availability.UNAVAILABLE } + ids.count { it !in snap.entities },
            available = haUsable(snap),
        )
    }

    fun weather(bindings: Bindings, snap: HaSnapshot): Pair<String, String>? {
        val e = snap.entities[bindings.roles[Role.WEATHER] ?: return null] ?: return null
        if (!haUsable(snap) || e.availability != Availability.NORMAL) return null
        val temp = EntityView.weatherTemperature(e) ?: return null
        return temp to EntityView.weatherCondition(e.state)
    }

    /** Für Kandidatenlisten in der Zuordnung: welche Domains passen zu einer Rolle. */
    fun candidatesFor(role: Role, entities: Collection<HaEntity>): List<HaEntity> = entities.filter { e ->
        when (role) {
            Role.LIGHT -> e.domain == "light"
            Role.TV_PLUG -> e.domain == "switch" || e.domain == "input_boolean"
            Role.TEMPERATURE -> EntityView.isTemperature(e)
            Role.MOVIE_SCENE -> e.domain == "scene" || e.domain == "script"
            Role.WEATHER -> e.domain == "weather"
            Role.MEDIA_PLAYER -> e.domain == "media_player"
        }
    }.sortedBy { it.friendlyName.lowercase() }

    fun windowCandidates(entities: Collection<HaEntity>) = entities.filter { it.domain == "binary_sensor" }
        .sortedWith(compareByDescending<HaEntity> { EntityView.isWindowSensor(it) }.thenBy { it.friendlyName.lowercase() })

    val SMART_HOME_DOMAINS = setOf("light", "switch", "input_boolean", "fan", "climate", "sensor", "binary_sensor", "media_player", "scene", "script")

    fun smartHomeCandidates(entities: Collection<HaEntity>) = entities.filter { it.domain in SMART_HOME_DOMAINS }
        .sortedWith(compareBy<HaEntity> { domainOrder(it.domain) }.thenBy { it.friendlyName.lowercase() })

    private fun domainOrder(d: String) = listOf("light", "switch", "input_boolean", "fan", "climate", "binary_sensor", "sensor", "media_player", "scene", "script").indexOf(d).let { if (it < 0) 99 else it }
}
