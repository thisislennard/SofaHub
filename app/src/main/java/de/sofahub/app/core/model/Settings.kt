package de.sofahub.app.core.model

import kotlinx.serialization.Serializable

/**
 * Gesamte lokale Konfiguration. Enthält niemals den HA-Token: der liegt verschlüsselt im [de.sofahub.app.core.storage.SecretStore].
 * Neue Felder immer mit Default anlegen; Struktur-Änderungen bekommen eine Migration in SettingsCodec und erhöhen [CURRENT_SCHEMA].
 */
@Serializable
data class AppSettings(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val setupCompleted: Boolean = false,
    val ha: HaConfig? = null,
    /** Rolle -> entity_id, z. B. LIGHT -> light.wohnzimmer. Rollen sind frei belegbar, nichts ist hart codiert. */
    val roles: Map<Role, String> = emptyMap(),
    /** Eigene Namen für die alten festen Kacheln (nur noch für die Übernahme in [homeTiles]). */
    val roleNames: Map<Role, String> = emptyMap(),
    /** Frei gewählte Kacheln der Startseite in Nutzerreihenfolge; null = noch nie bearbeitet (aus den Rollen abgeleitet). */
    val homeTiles: List<HomeTile>? = null,
    /** Szenen/Skripte als Knopfleiste auf der Startseite. */
    val sceneBar: List<String> = emptyList(),
    /** Welche Bereiche die Startseite zeigt und in welcher Reihenfolge. */
    val homeLayout: HomeLayout = HomeLayout(),
    /** Fire-TV-Zustand (an/aus, laufende App) als Entitäten an Home Assistant melden. */
    val reportToHa: Boolean = true,
    /** Ausdrücklich als Fenster/Tür zugeordnete binary_sensor-Entitäten. */
    val windowEntities: List<String> = emptyList(),
    /** Weitere Geräte für die Smart-Home-Seite, in Nutzerreihenfolge. */
    val smartHomeEntities: List<String> = emptyList(),
    /** HA-Kalender (calendar.*), deren Termine von heute und morgen auf der Startseite stehen – auch Müllkalender. */
    val calendarEntities: List<String> = emptyList(),
    /** Entitäten, deren Zustandswechsel kurz in der Statusinsel erscheinen (z. B. Waschmaschine fertig). */
    val noticeEntities: List<String> = emptyList(),
    /** Meldungen auch über anderen Apps zeigen (Netflix & Co.; braucht den Hintergrunddienst). */
    val noticesOverApps: Boolean = true,
    /** Manuelle Raumzuordnung (entity_id -> Raumname), falls die HA-Registry nicht lesbar ist oder abweicht. */
    val manualRooms: Map<String, String> = emptyMap(),
    val favorites: List<FavoriteApp> = emptyList(),
    /** Zuletzt geöffnete Apps (Paketnamen, neueste zuerst) – auch außerhalb von Sofa Hub gestartete. */
    val recentApps: List<String> = emptyList(),
    /** Letztes Spiegeln (Fire-TV-Empfang im Vordergrund): Beginn und Dauer, für die Spiegeln-Kachel. */
    val lastMirrorEpochMs: Long = 0,
    val lastMirrorMinutes: Int = 0,
    val note: String = "",
    /** Gäste-WLAN als QR-Code-Kachel auf der Startseite; null = keine Kachel. */
    val guestWifi: GuestWifi? = null,
    val photos: List<PhotoRef> = emptyList(),
    val slideshowSeconds: Int = 8,
    /** Eigenes Hintergrundbild (Datei im App-Speicher oder Dokument-URI); null = Standard-Hintergrund des Designs. */
    val background: PhotoRef? = null,
    /** Abdunkelung des Hintergrundbilds in Prozent, damit Kacheln und Text lesbar bleiben. */
    val backgroundDimPercent: Int = 55,
    /** Farbdesign (siehe ui/theme/ColorThemes). */
    val colorTheme: String = "anthrazit",
    /** Begrüßung nach Tageszeit und wärmerer, gedämpfter Bildschirm am Abend. */
    val greeting: Boolean = true,
    /** Dezente Wetter-Animation hinter der Startseite (Zustand der Wetter-Entität). */
    val weatherMood: Boolean = true,
    /** Kiosk-Modus: beim Einschalten starten, Home-Taste führt zurück zu Sofa Hub (braucht den Bedienungshilfe-Dienst). */
    val kioskMode: Boolean = false,
    /** HA-Aktionen, wenn der Fire TV ausgeschaltet wird (Standby / Bildschirm aus). */
    val powerOffActions: List<HaAction> = emptyList(),
    /** HA-Aktionen, wenn der Fire TV wieder eingeschaltet wird. */
    val powerOnActions: List<HaAction> = emptyList(),
    /** HA-Aktionen, wenn eine bestimmte App geöffnet wird (z. B. Netflix → Szene „Kino“). */
    val appRules: List<AppRule> = emptyList(),
    val autoUpdateCheck: Boolean = true,
    val lastUpdateCheckEpochMs: Long = 0,
    val pendingUpdate: PendingUpdate? = null,
    /** Demo-Modus zeigt Beispieldaten und ist in der Oberfläche immer als "Demo" gekennzeichnet. */
    val demoMode: Boolean = false,
) {
    companion object {
        const val CURRENT_SCHEMA = 1
        const val MAX_FAVORITES = 12
        /** Sichtbare Favoriten auf einmal; weitere erreicht man durch Weiterblättern. */
        const val VISIBLE_FAVORITES = 6
        const val MAX_HOME_TILES = 8
        const val MAX_SCENE_BAR = 8
        const val MAX_NOTE_LENGTH = 500
        const val MAX_PHOTOS = 200
        const val MAX_RECENT_APPS = 12
        const val MAX_TILE_NAME = 28

        fun defaultTileName(role: Role) = when (role) {
            Role.LIGHT -> "Wohnzimmerlicht"
            Role.TV_PLUG -> "TV-Steckdose"
            Role.TEMPERATURE -> "Raumtemperatur"
            Role.MOVIE_SCENE -> "Filmabend"
            Role.WEATHER -> "Wetter"
            Role.MEDIA_PLAYER -> "Fernseher"
        }

        /** [pkg] nach vorn, Duplikate raus, Liste begrenzt. */
        fun pushRecent(recent: List<String>, pkg: String): List<String> =
            (listOf(pkg) + recent.filter { it != pkg }).take(MAX_RECENT_APPS)
    }
}

/** Eine Kachel der Startseite: beliebige HA-Entität, optional mit eigenem Namen. */
@Serializable
data class HomeTile(val entityId: String, val name: String? = null)

/** Bereiche der Startseite unterhalb der Kachelreihe, verschiebbar. */
@Serializable
enum class HomeBlock { SCENES, APPS, HOUSE }

@Serializable
data class HomeLayout(
    val showMirror: Boolean = true,
    val showScenes: Boolean = true,
    val showApps: Boolean = true,
    val showHouse: Boolean = true,
    val order: List<HomeBlock> = listOf(HomeBlock.SCENES, HomeBlock.APPS, HomeBlock.HOUSE),
) {
    /** Reihenfolge ohne Lücken/Doppelte (auch nach einem Update mit neuen Bereichen). */
    val blocks: List<HomeBlock> get() = (order + HomeBlock.entries).distinct()

    fun shows(block: HomeBlock) = when (block) {
        HomeBlock.SCENES -> showScenes
        HomeBlock.APPS -> showApps
        HomeBlock.HOUSE -> showHouse
    }
}

/**
 * Kacheln, die die Startseite zeigt: eigene Liste, sonst die früheren festen Kacheln aus den Rollen (Licht, Steckdose,
 * Temperatur, Filmabend) mit ihren eigenen Namen – so bleibt nach dem Update alles wie gewohnt.
 */
fun AppSettings.effectiveHomeTiles(roles: Map<Role, String> = this.roles, demo: Boolean = false): List<HomeTile> =
    (if (demo) null else homeTiles) ?: listOf(Role.LIGHT, Role.TV_PLUG, Role.TEMPERATURE, Role.MOVIE_SCENE).mapNotNull { r ->
        roles[r]?.let { HomeTile(it, tileName(r)) }
    }

/** Name einer früheren festen Kachel (vor den freien Kacheln): eigener Name, sonst Standard. */
fun AppSettings.tileName(role: Role): String = roleNames[role]?.trim()?.takeIf { it.isNotEmpty() } ?: AppSettings.defaultTileName(role)

/** Eine einzelne, vom Nutzer gewählte HA-Aktion für Automationen. */
@Serializable
data class HaAction(
    val entityId: String,
    val kind: ActionKind,
    /** Nur Lichter beim Einschalten: Farbe (RGB) oder Weißton (Kelvin) und Helligkeit in Prozent. */
    val rgb: List<Int>? = null,
    val kelvin: Int? = null,
    val brightness: Int? = null,
) {
    val hasLook: Boolean get() = rgb != null || kelvin != null || brightness != null
}

@Serializable
enum class ActionKind {
    /** Einschalten (light/switch/fan/input_boolean/media_player). */
    ON,
    /** Ausschalten. */
    OFF,
    /** Szene oder Skript starten. */
    ACTIVATE,
}

@Serializable
data class AppRule(val packageName: String, val actions: List<HaAction> = emptyList())

@Serializable
data class HaConfig(
    val baseUrl: String,
    /** Nutzer hat unverschlüsseltes http für seine lokale Instanz bewusst bestätigt. */
    val allowHttp: Boolean = false,
)

@Serializable
enum class Role {
    LIGHT,
    TV_PLUG,
    TEMPERATURE,
    MOVIE_SCENE,
    WEATHER,
    MEDIA_PLAYER,
}

@Serializable
data class FavoriteApp(
    val packageName: String,
    val customLabel: String? = null,
)

/**
 * Verweis auf ein Bild: entweder eine per Systemdialog gewählte content://-URI ([persisted] = dauerhafte Leserechte erhalten)
 * oder eine vom Handy hochgeladene Datei im App-Speicher ([localFile], Dateiname ohne Pfad).
 */
@Serializable
data class PhotoRef(
    val uri: String? = null,
    val persisted: Boolean = false,
    val localFile: String? = null,
)

@Serializable
data class PendingUpdate(
    val expectedVersionCode: Long,
    val versionName: String,
    val startedEpochMs: Long,
)
