package de.sofahub.app.feature

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sofahub.app.core.model.HaConnection
import de.sofahub.app.core.model.HaSnapshot
import de.sofahub.app.feature.dashboard.HomeScreen
import de.sofahub.app.feature.dashboard.LoadingScreen
import de.sofahub.app.feature.favorites.FavoritesScreen
import de.sofahub.app.feature.home.SmartHomeScreen
import de.sofahub.app.feature.mirror.MirrorScreen
import de.sofahub.app.feature.photos.SlideshowScreen
import de.sofahub.app.feature.settings.PickerScreen
import de.sofahub.app.feature.settings.SettingsScreen
import de.sofahub.app.feature.settings.UpdatesScreen
import de.sofahub.app.feature.setup.PairingScreen
import de.sofahub.app.feature.setup.SetupScreen
import de.sofahub.app.ui.components.Ambient
import de.sofahub.app.ui.components.Backdrop
import de.sofahub.app.ui.components.EveningTint
import de.sofahub.app.ui.components.WeatherMood
import de.sofahub.app.ui.components.eveningWarmth
import de.sofahub.app.ui.components.Island
import de.sofahub.app.ui.components.IslandStatus
import de.sofahub.app.ui.components.IslandTone
import de.sofahub.app.ui.components.LocalFocusRegistry
import de.sofahub.app.ui.theme.DesignScale

@Composable
fun AppRoot(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val routes by vm.routes.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val ha by vm.ha.collectAsStateWithLifecycle()

    val bgRef = settings?.background
    val background: ImageBitmap? by produceState<ImageBitmap?>(null, bgRef) {
        value = bgRef?.let { vm.c.photos.load(it, 1920, 1080)?.asImageBitmap() }
    }

    DesignScale {
        val route = routes.lastOrNull()
        val ambient = when (route) {
            is Route.SmartHome -> Ambient.SmartHome
            is Route.Mirror -> Ambient.Mirror
            is Route.Settings, is Route.Picker -> Ambient.Settings
            is Route.Updates -> Ambient.Updates
            else -> Ambient.Main
        }
        val weatherState = settings?.takeIf { route == Route.Home && it.weatherMood && !vm.c.ha.isDemo }
            ?.roles?.get(de.sofahub.app.core.model.Role.WEATHER)?.let { ha.entities[it]?.state }
        val weatherStill = remember(weatherState, de.sofahub.app.ui.theme.Sh.light) { de.sofahub.app.ui.components.weatherBackdrop(weatherState, de.sofahub.app.ui.theme.Sh.light) }
        Backdrop(ambient, if (route is Route.Slideshow) null else background, settings?.backgroundDimPercent ?: 55, weatherStill) {
            val s = settings
            // Stimmung: Wetter nur hinter der Startseite, abends ein warmer Schleier über allem außer der Diashow.
            if (weatherState != null) WeatherMood(weatherState)
            if (s != null && s.greeting && route !is Route.Slideshow) {
                val now = de.sofahub.app.feature.dashboard.rememberNow()
                val cal = remember(now / 60_000) { java.util.Calendar.getInstance() }
                EveningTint(eveningWarmth(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)))
            }
            if (s == null || route == null) {
                LoadingScreen()
            } else {
                // Vorwärts wächst die neue Seite aus der Mitte heraus, zurück zieht sie sich wieder zusammen.
                val depth = routes.size
                val lastDepth = remember { intArrayOf(depth) }
                val forward = depth >= lastDepth[0]
                SideEffect { lastDepth[0] = depth }
                AnimatedContent(
                    targetState = route,
                    transitionSpec = {
                        val ease = CubicBezierEasing(.2f, .8f, .2f, 1f)
                        if (forward) {
                            (fadeIn(tween(320, 60)) + scaleIn(tween(460, easing = ease), initialScale = .9f)) togetherWith
                                (fadeOut(tween(180)) + scaleOut(tween(300), targetScale = 1.05f))
                        } else {
                            (fadeIn(tween(320, 60)) + scaleIn(tween(460, easing = ease), initialScale = 1.06f)) togetherWith
                                (fadeOut(tween(180)) + scaleOut(tween(300), targetScale = .92f))
                        }
                    },
                    contentKey = { it.key },
                    label = "route",
                ) { r ->
                    val registry = remember(r.key) { vm.registry(r) }
                    CompositionLocalProvider(LocalFocusRegistry provides registry) {
                        when (r) {
                            Route.Home -> HomeScreen(vm, s, ha)
                            is Route.SmartHome -> SmartHomeScreen(vm, s, ha, r.room)
                            is Route.Mirror -> MirrorScreen(vm, r.failed)
                            Route.Favorites -> FavoritesScreen(vm, s)
                            is Route.Settings -> SettingsScreen(vm, s, ha, r.section, r.subpage)
                            Route.Updates -> UpdatesScreen(vm)
                            is Route.Setup -> SetupScreen(vm, s, ha, r.step)
                            is Route.Picker -> PickerScreen(vm, s, ha, r.target)
                            Route.Slideshow -> SlideshowScreen(vm, s)
                            Route.Pairing -> PairingScreen(vm)
                            Route.Printer -> de.sofahub.app.feature.dashboard.PrinterScreen(vm, ha)
                        }
                    }
                }
                if (route !is Route.Slideshow && route !is Route.Setup) {
                    // Die Insel ist eine Benachrichtigung: sie erscheint nur bei Bestätigungen, Meldungen oder einem echten
                    // Problem – nie für „verbunden“, „verbinde …“ oder „Demo“ (die Demo steht ohnehin unten in der Fußzeile).
                    Island(rememberIslandProblem(ha, vm.c.ha.isDemo), toast, Modifier.align(Alignment.TopCenter).padding(top = 58.dp).zIndex(10f))
                } else if (toast != null) {
                    Island(null, toast, Modifier.align(Alignment.TopCenter).padding(top = 58.dp).zIndex(10f))
                }
            }
        }
    }
}

/**
 * Problem, das oben angezeigt werden soll. Ein kurzer Aussetzer (Neuverbindung) bleibt unsichtbar: „antwortet nicht“
 * erscheint erst, wenn es länger als [ISLAND_PROBLEM_DELAY_MS] anhält; Token-Probleme sofort.
 */
@Composable
private fun rememberIslandProblem(ha: HaSnapshot, demo: Boolean): IslandStatus? {
    val status = islandStatus(ha, demo).takeIf { it.tone == IslandTone.Warn }
    val delayed = ha.connection is HaConnection.Retrying
    val shown by produceState<IslandStatus?>(null, status?.text) {
        if (status != null && delayed) kotlinx.coroutines.delay(ISLAND_PROBLEM_DELAY_MS)
        value = status
    }
    return shown
}

const val ISLAND_PROBLEM_DELAY_MS = 15_000L

fun islandStatus(ha: HaSnapshot, demo: Boolean): IslandStatus = when {
    demo -> IslandStatus("Demo · Beispieldaten", IslandTone.Neutral)
    else -> when (val c = ha.connection) {
        HaConnection.Connected -> IslandStatus("Home Assistant verbunden", IslandTone.Ok)
        HaConnection.Connecting -> IslandStatus("Verbinde mit Home Assistant …", IslandTone.Busy)
        HaConnection.NotConfigured -> IslandStatus("Home Assistant nicht eingerichtet", IslandTone.Neutral)
        HaConnection.Disconnected -> IslandStatus("Home Assistant getrennt", IslandTone.Neutral)
        HaConnection.AuthFailed -> IslandStatus("Token ungültig – bitte neu einrichten", IslandTone.Warn)
        HaConnection.TokenUnreadable -> IslandStatus("Token nicht lesbar – bitte neu einrichten", IslandTone.Warn)
        is HaConnection.Retrying -> IslandStatus("Home Assistant antwortet nicht", IslandTone.Warn)
    }
}
