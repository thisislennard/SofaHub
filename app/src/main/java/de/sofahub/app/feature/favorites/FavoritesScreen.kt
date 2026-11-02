package de.sofahub.app.feature.favorites

import androidx.compose.foundation.layout.width
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sofahub.app.core.model.AppSettings
import de.sofahub.app.core.model.FavoriteApp
import de.sofahub.app.feature.MainViewModel
import de.sofahub.app.ui.components.FocusOnEnter
import de.sofahub.app.ui.components.Footer
import de.sofahub.app.ui.components.LocalFocusRegistry
import de.sofahub.app.ui.components.PageHeader
import de.sofahub.app.ui.components.SectionTitle
import de.sofahub.app.ui.components.ShButton
import de.sofahub.app.ui.components.Tile
import de.sofahub.app.ui.components.TileStyle
import de.sofahub.app.ui.components.Txt
import de.sofahub.app.ui.components.glass
import de.sofahub.app.ui.theme.Icon
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon

@Composable
fun FavoritesScreen(vm: MainViewModel, s: AppSettings) {
    val installed by vm.apps.collectAsStateWithLifecycle()
    val favs = s.favorites.take(AppSettings.MAX_FAVORITES)
    var picked by remember { mutableStateOf<Int?>(null) }
    val registry = LocalFocusRegistry.current

    fun save(list: List<FavoriteApp>) = vm.updateSettings { it.copy(favorites = list) }
    fun move(dir: Int) {
        val i = picked ?: return
        val j = i + dir
        if (j !in favs.indices) return
        val list = favs.toMutableList()
        list[i] = favs[j].also { list[j] = favs[i] }
        save(list)
        picked = j
        registry.focus("fav$j")
    }
    fun label(f: FavoriteApp) = f.customLabel ?: installed.firstOrNull { it.packageName == f.packageName }?.label ?: f.packageName

    FocusOnEnter(if (favs.isEmpty()) "add0" else "fav0", "done")
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 54.dp)) {
        PageHeader("Favoriten bearbeiten", onBack = { vm.back() }) {
            ShButton("done", "Fertig", primary = true) { vm.back() }
        }
        Spacer(Modifier.height(44.dp))
        SectionTitle("Auf der Startseite", "${favs.size} von ${AppSettings.MAX_FAVORITES} Plätzen belegt · sechs sichtbar, die Reihe blättert weiter")
        Spacer(Modifier.height(40.dp))
        androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().height(200.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), contentPadding = PaddingValues(top = 26.dp)) {
            items(favs.size, key = { favs[it].packageName }) { i ->
                val f = favs[i]
                val lifted = picked == i
                val missing = installed.isNotEmpty() && installed.none { it.packageName == f.packageName }
                val art = de.sofahub.app.feature.rememberAppArt(vm, f.packageName)
                val banner = art?.banner != null && !missing
                Tile(
                    key = "fav$i",
                    modifier = Modifier.width(264.dp).fillMaxHeight()
                        .offset { androidx.compose.ui.unit.IntOffset(0, if (lifted) (-22).dp.roundToPx() else 0) }
                        .onPreviewKeyEvent { ev ->
                            if (!lifted || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (ev.nativeKeyEvent.keyCode) {
                                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { move(-1); true }
                                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { move(1); true }
                                else -> false
                            }
                        },
                    radius = 26,
                    style = if (missing) TileStyle.Dashed else TileStyle.Glass,
                    background = if (lifted) Brush.verticalGradient(listOf(Color(0x332DD4BF), Color(0x122DD4BF))) else null,
                    borderColor = if (lifted) Sh.tealText else null,
                    focusScale = if (lifted) 1.08f else Sh.focusScale,
                    padding = if (banner) PaddingValues(0.dp) else PaddingValues(horizontal = 24.dp, vertical = 22.dp),
                    onClick = {
                        if (lifted) {
                            picked = null
                            vm.toast("Reihenfolge gespeichert")
                        } else picked = i
                    },
                ) {
                    if (banner) {
                        with(de.sofahub.app.feature.AppBannerScope) { AppBannerIn(art, label(f), badge = "${i + 1}") }
                    } else Column(Modifier.fillMaxSize().alpha(if (missing) .75f else 1f), verticalArrangement = Arrangement.SpaceBetween) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            de.sofahub.app.feature.AppIcon(art, label(f), f.packageName, 56, 16)
                            Txt("${i + 1}", Sh.style(20, Sh.W650, Sh.hint))
                        }
                        Column {
                            Txt(label(f), Sh.style(25, FontWeight.SemiBold))
                            if (missing) Txt("Nicht installiert", Sh.style(19, color = Sh.coralText))
                        }
                    }
                }
            }
            items((AppSettings.VISIBLE_FAVORITES - favs.size).coerceAtLeast(0)) {
                Box(
                    Modifier.width(264.dp).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.fillMaxSize().glass(26).alpha(.4f))
                    Txt("Freier Platz", Sh.style(22, color = Sh.muted))
                }
            }
        }
        Spacer(Modifier.height(40.dp))
        Row(
            Modifier.fillMaxWidth().glass(30).padding(start = 34.dp, end = 18.dp, top = 18.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val p = picked?.takeIf { it in favs.indices }
            if (p != null) {
                Txt("${label(favs[p])} ist aufgenommen. Mit ◀ ▶ verschieben, OK legt ab.", Sh.style(25), Modifier.weight(1f))
                ShButton("left", "Links", icon = ShIcon.ChevronLeft) { move(-1) }
                ShButton("right", "Rechts", trailingIcon = ShIcon.ChevronRight) { move(1) }
                ShButton("remove", "Entfernen", icon = ShIcon.Trash, color = Sh.dangerText) {
                    val name = label(favs[p])
                    save(favs.filterIndexed { idx, _ -> idx != p })
                    picked = null
                    vm.toast("$name entfernt")
                    registry.focus(if (favs.size > 1) "fav0" else "add0")
                }
                ShButton("drop", "Ablegen", primary = true) {
                    picked = null
                    vm.toast("Reihenfolge gespeichert")
                    registry.focus("fav$p")
                }
            } else {
                Txt("Wähle eine App mit OK, um sie zu verschieben oder zu entfernen.", Sh.style(25, color = Sh.soft), Modifier.weight(1f).padding(vertical = 20.dp))
            }
        }
        Spacer(Modifier.height(48.dp))
        SectionTitle("Installierte Apps", if (installed.isEmpty()) "Werden gesucht …" else "OK fügt hinzu")
        Spacer(Modifier.height(24.dp))
        val available = installed.filter { app -> favs.none { it.packageName == app.packageName } }
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(12.dp),
        ) {
            items(available.size, key = { available[it].packageName }) { i ->
                val app = available[i]
                Tile(
                    key = "add$i",
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    radius = 24,
                    padding = PaddingValues(0.dp),
                    onClick = {
                        if (favs.size >= AppSettings.MAX_FAVORITES) {
                            vm.toast("Maximal ${AppSettings.MAX_FAVORITES} Favoriten", "Erst eine App entfernen", warn = true)
                        } else {
                            save(favs + FavoriteApp(app.packageName))
                            vm.toast("${app.label} hinzugefügt")
                        }
                    },
                ) {
                    val art = de.sofahub.app.feature.rememberAppArt(vm, app.packageName)
                    if (art?.banner != null) {
                        with(de.sofahub.app.feature.AppBannerScope) { AppBannerIn(art, app.label, badge = "+") }
                    } else Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            de.sofahub.app.feature.AppIcon(art, app.label, app.packageName, 48, 14)
                            Box(Modifier.size(38.dp).clip(CircleShape).background(Sh.veil(0.122f)), contentAlignment = Alignment.Center) {
                                Icon(ShIcon.Plus, Sh.bright, 20)
                            }
                        }
                        Txt(app.label, Sh.style(22, FontWeight.SemiBold))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Footer(listOf("OK" to "Aufnehmen / Ablegen", "◀ ▶" to "Verschieben", "↩" to "Fertig"))
    }
}
