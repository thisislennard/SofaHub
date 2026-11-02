package de.sofahub.app.feature.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sofahub.app.BuildConfig
import de.sofahub.app.feature.MainViewModel
import de.sofahub.app.feature.dashboard.formatTime
import de.sofahub.app.integration.updates.UpdateError
import de.sofahub.app.integration.updates.UpdateOffer
import de.sofahub.app.integration.updates.UpdateState
import de.sofahub.app.ui.components.FocusOnEnter
import de.sofahub.app.ui.components.Footer
import de.sofahub.app.ui.components.PageHeader
import de.sofahub.app.ui.components.ShButton
import de.sofahub.app.ui.components.Spinner
import de.sofahub.app.ui.components.Txt
import de.sofahub.app.ui.components.glass
import de.sofahub.app.ui.theme.Icon
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun UpdatesScreen(vm: MainViewModel) {
    val state by vm.updates.collectAsStateWithLifecycle()
    // Token-Dialog auf Bildschirmebene (in der Karte wäre er zusammengequetscht und nicht bedienbar).
    var tokenVersion by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var editingToken by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val offer: UpdateOffer? = when (val st = state) {
        is UpdateState.Available -> st.offer
        is UpdateState.Downloading -> st.offer
        is UpdateState.Verifying -> st.offer
        is UpdateState.AwaitingPermission -> st.offer
        is UpdateState.ReadyToInstall -> st.offer
        is UpdateState.InstallerLaunched -> st.offer
        is UpdateState.Error -> st.offer
        else -> null
    }
    FocusOnEnter("primary")
    // Wechselt der Zustand, verschwindet der fokussierte Button: Fokus auf die neue Hauptaktion setzen.
    val registry = de.sofahub.app.ui.components.LocalFocusRegistry.current
    androidx.compose.runtime.LaunchedEffect(state::class) {
        kotlinx.coroutines.delay(80)
        registry.focus("primary")
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 54.dp)) {
        PageHeader("Updates", onBack = { vm.back() })
        Spacer(Modifier.height(40.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().glass(36).padding(horizontal = 48.dp, vertical = 44.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt("Installiert", Sh.style(21, color = Sh.hint))
                        Txt(BuildConfig.VERSION_NAME, Sh.style(44, FontWeight.Medium, mono = true))
                    }
                    if (offer != null) {
                        Icon(ShIcon.ArrowRight, Sh.muted, 34, Modifier.align(Alignment.Bottom).padding(bottom = 12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Txt("Verfügbar", Sh.style(21, color = Sh.hint))
                            Txt(offer.manifest.versionName, Sh.style(44, FontWeight.Medium, Sh.tealText, mono = true))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 36.dp).height(1.dp).background(Sh.veil(0.102f)))
                if (offer != null) {
                    Txt("Was ist neu in ${offer.manifest.versionName}", Sh.style(30, Sh.W650))
                    Spacer(Modifier.height(6.dp))
                    Txt("Veröffentlicht ${offer.publishedAt.take(10)} · ${mb(offer.manifest.apkSizeBytes)}", Sh.style(21, color = Sh.hint))
                    Spacer(Modifier.height(30.dp))
                    val notes = offer.notes.lines().map { it.trim().removePrefix("- ").removePrefix("* ") }.filter { it.isNotBlank() && !it.startsWith("#") }.take(8)
                    val colors = listOf(Sh.teal, Sh.sun, Sh.violet, Sh.coralText)
                    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        notes.forEachIndexed { i, n ->
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Box(Modifier.padding(top = 13.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(colors[i % colors.size]))
                                Txt(n, Sh.style(25, color = Sh.soft, lineHeight = 1.4f), maxLines = 2)
                            }
                        }
                        if (notes.isEmpty()) Txt("Keine Release Notes angegeben.", Sh.style(24, color = Sh.text2))
                    }
                } else {
                    Txt("Updates kommen als signierte APK von GitHub Releases.", Sh.style(27, color = Sh.text2), maxLines = 2)
                    Spacer(Modifier.height(16.dp))
                    Txt("Quelle: github.com/${BuildConfig.UPDATE_REPO}", Sh.style(22, color = Sh.hint, mono = true))
                    Spacer(Modifier.height(16.dp))
                    Txt("Installiert wird nie automatisch: Fire TV fragt immer nach einer Bestätigung.", Sh.style(22, color = Sh.hint), maxLines = 2)
                }
                Spacer(Modifier.weight(1f))
                GitHubTokenRow(vm, tokenVersion, onChanged = { tokenVersion++ }, onEdit = { editingToken = true })
            }
            Column(Modifier.width(820.dp).fillMaxHeight().glass(36).padding(48.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                StatePanel(vm, state)
            }
        }
        Spacer(Modifier.height(26.dp))
        Footer(listOf("OK" to "Auswählen", "↩" to "Einstellungen"))
    }
    if (editingToken) GitHubTokenDialog(vm, onSaved = { tokenVersion++ }, onClose = { editingToken = false })
    }
}

private fun mb(bytes: Long) = String.format(Locale.GERMANY, "%.1f MB", bytes / 1_048_576.0)

@Composable
private fun Badge(icon: ShIcon?, colors: List<Color>, spinner: Boolean = false) {
    Box(Modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) {
        if (spinner) Spinner(46, 4) else icon?.let { Icon(it, Color.White, 46) }
    }
}

@Composable
private fun ColumnScope.StatePanel(vm: MainViewModel, state: UpdateState) {
    val u = vm.c.updates
    val green = listOf(Color(0xFF4ADE80), Sh.tealFill)
    when (state) {
        UpdateState.Idle -> {
            Badge(ShIcon.Download, listOf(Sh.veil(0.078f), Sh.veil(0.078f)))
            Txt("Nach Updates suchen", Sh.style(44, Sh.W650, letterSpacing = (-0.03).em))
            Txt("Prüft das neueste Release auf GitHub.", Sh.style(25, color = Sh.text2))
            Spacer(Modifier.weight(1f))
            ShButton("primary", "Jetzt prüfen", primary = true, height = 76, fontSize = 26) { u.check() }
        }
        UpdateState.Checking -> {
            Badge(null, listOf(Sh.veil(0.078f), Sh.veil(0.078f)), spinner = true)
            Txt("Suche nach Updates …", Sh.style(44, Sh.W650, letterSpacing = (-0.03).em))
            Txt("Einen Moment.", Sh.style(25, color = Sh.text2))
            Spacer(Modifier.weight(1f))
            ShButton("primary", "Bitte warten", height = 76, fontSize = 26, enabled = false) {}
        }
        is UpdateState.UpToDate -> {
            Badge(ShIcon.Check, green)
            Txt("Sofa Hub ist aktuell", Sh.style(44, Sh.W650, letterSpacing = (-0.03).em))
            Txt("Zuletzt geprüft heute um ${formatTime(state.checkedEpochMs)}.", Sh.style(25, color = Sh.text2))
            Spacer(Modifier.weight(1f))
            ShButton("primary", "Erneut prüfen", height = 76, fontSize = 26) { u.check() }
        }
        is UpdateState.Available -> {
            Badge(ShIcon.Download, listOf(Sh.sky, Color(0xFF7C6CF6)))
            Txt("Update bereit zum Laden", Sh.style(44, Sh.W650, letterSpacing = (-0.03).em))
            Txt("Der Download läuft im Hintergrund weiter, du kannst Sofa Hub normal nutzen.", Sh.style(25, color = Sh.text2, lineHeight = 1.4f), maxLines = 2)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ShButton("primary", "Herunterladen", primary = true, height = 76, fontSize = 26) { u.download() }
                ShButton("later", "Später", height = 76, fontSize = 26) { vm.back() }
            }
        }
        is UpdateState.Downloading -> {
            val total = state.offer.manifest.apkSizeBytes
            val pct = if (total > 0) (state.bytes * 100 / total).toInt() else 0
            Txt("WIRD HERUNTERGELADEN", Sh.style(24, FontWeight.SemiBold, Sh.tealText, letterSpacing = .08.em))
            Row(verticalAlignment = Alignment.Bottom) {
                Txt("$pct", Sh.style(110, FontWeight.SemiBold, letterSpacing = (-0.05).em, lineHeight = 1f))
                Txt("%", Sh.style(46, color = Sh.text3), Modifier.padding(start = 10.dp, bottom = 12.dp))
            }
            ProgressBar(pct / 100f)
            Txt("${mb(state.bytes)} von ${mb(total)}", Sh.style(22, color = Sh.hint, mono = true))
            Spacer(Modifier.weight(1f))
            ShButton("primary", "Abbrechen", height = 76, fontSize = 26) { u.cancelDownload() }
        }
        is UpdateState.Verifying -> {
            Badge(null, listOf(Sh.veil(0.078f), Sh.veil(0.078f)), spinner = true)
            Txt("Prüfe Download …", Sh.style(44, Sh.W650, letterSpacing = (-0.03).em))
            Txt("Prüfsumme, Paketname, Version und Signatur.", Sh.style(25, color = Sh.text2))
            Spacer(Modifier.weight(1f))
        }
        is UpdateState.AwaitingPermission -> {
            Badge(ShIcon.Info, listOf(Sh.sky, Color(0xFF7C6CF6)))
            Txt("Freigabe erforderlich", Sh.style(44, Sh.W650, letterSpacing = (-0.03).em))
            Txt(
                "Fire TV muss Sofa Hub einmalig erlauben, Apps zu installieren: Einstellungen › Mein Fire TV › Entwickleroptionen › Apps unbekannter Herkunft installieren › Sofa Hub.",
                Sh.style(24, color = Sh.text2, lineHeight = 1.4f), maxLines = 4,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ShButton("primary", "Freigabe öffnen", primary = true, height = 76, fontSize = 26) {
                    if (!u.openPermissionSettings()) vm.toast("Einstellung nicht direkt erreichbar", "Pfad bitte manuell öffnen", warn = true)
                }
                ShButton("recheck", "Erneut prüfen", height = 76, fontSize = 26) { u.recheckPermission() }
            }
        }
        is UpdateState.ReadyToInstall -> {
            Badge(ShIcon.Check, green)
            Txt("Bereit zur Installation", Sh.style(44, Sh.W650, letterSpacing = (-0.03).em))
            InfoNote("Gleich erscheint ein Fenster von Fire TV. Wähle dort „Installieren“. Sofa Hub wird dabei beendet – danach einfach wieder öffnen.")
            Spacer(Modifier.weight(1f))
            ShButton("primary", "Jetzt installieren", primary = true, height = 76, fontSize = 26) { u.install() }
        }
        is UpdateState.InstallerLaunched -> {
            Badge(null, listOf(Sh.veil(0.078f), Sh.veil(0.078f)), spinner = true)
            Txt("Installation läuft", Sh.style(44, Sh.W650, letterSpacing = (-0.03).em))
            InfoNote("Bestätige im Fire-TV-Fenster mit „Installieren“. Danach Sofa Hub wieder öffnen – die neue Version bestätigt das Update.")
            Spacer(Modifier.weight(1f))
        }
        is UpdateState.Installed -> {
            Badge(ShIcon.Check, green)
            Txt("Update installiert", Sh.style(44, Sh.W650, letterSpacing = (-0.03).em))
            Txt("Sofa Hub ${state.versionName} läuft. Deine Einstellungen sind erhalten.", Sh.style(25, color = Sh.text2), maxLines = 2)
            Spacer(Modifier.weight(1f))
            ShButton("primary", "OK", primary = true, height = 76, fontSize = 26) { u.dismiss() }
        }
        is UpdateState.Error -> {
            Badge(ShIcon.Warning, listOf(Color(0xFFFF9A7A), Color(0xFFE5484D)))
            Txt(errorTitle(state.error), Sh.style(44, Sh.W650, letterSpacing = (-0.03).em), maxLines = 2)
            Txt(errorText(state.error) + (state.detail?.let { " ($it)" } ?: ""), Sh.style(25, color = Sh.text2, lineHeight = 1.4f), maxLines = 4)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (state.error != UpdateError.DEBUG_BUILD) {
                    ShButton("primary", "Erneut versuchen", primary = true, height = 76, fontSize = 26) { u.retry() }
                }
                ShButton("close", "Schließen", height = 76, fontSize = 26) { u.dismiss() }
            }
        }
    }
}

@Composable
private fun InfoNote(text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0x1F60A5FA)).border(1.dp, Color(0x5960A5FA), RoundedCornerShape(24.dp))
            .padding(horizontal = 26.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(ShIcon.Info, Sh.skyText, 34)
        Txt(text, Sh.style(24, color = Sh.soft, lineHeight = 1.4f), maxLines = 3)
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    val t = rememberInfiniteTransition(label = "shine")
    val x by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp)).background(Sh.veil(0.102f))) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0.01f, 1f)).clip(RoundedCornerShape(11.dp))
                .background(Brush.horizontalGradient(listOf(Sh.teal, Sh.sky, Sh.violet)))
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x59FFFFFF), Color.Transparent), startX = size.width * x, endX = size.width * (x + 1)))
                },
        )
    }
}

private fun errorTitle(e: UpdateError) = when (e) {
    UpdateError.NETWORK, UpdateError.DOWNLOAD_FAILED -> "Download fehlgeschlagen"
    UpdateError.TOKEN_INVALID -> "GitHub-Token ungültig"
    UpdateError.RATE_LIMITED -> "GitHub-Limit erreicht"
    UpdateError.NO_RELEASE -> "Kein Release gefunden"
    UpdateError.INVALID_METADATA, UpdateError.ASSET_MISSING -> "Release unvollständig"
    UpdateError.MIN_SDK -> "Gerät zu alt für dieses Update"
    UpdateError.BLOCKED -> "Unsichere Weiterleitung blockiert"
    UpdateError.NO_SPACE -> "Zu wenig Speicher"
    UpdateError.SIZE_MISMATCH, UpdateError.HASH_MISMATCH -> "Download beschädigt"
    UpdateError.WRONG_PACKAGE -> "Falsches Paket"
    UpdateError.SIGNATURE_MISMATCH -> "Signatur passt nicht"
    UpdateError.NOT_NEWER -> "Keine neuere Version"
    UpdateError.INSTALL_FAILED -> "Installation fehlgeschlagen"
    UpdateError.INSTALL_ABORTED -> "Installation abgebrochen"
    UpdateError.DEBUG_BUILD -> "Debug-Version"
}

private fun errorText(e: UpdateError) = when (e) {
    UpdateError.NETWORK, UpdateError.DOWNLOAD_FAILED -> "Keine Verbindung zu GitHub. Prüfe das WLAN des Fire TV und versuche es erneut."
    UpdateError.TOKEN_INVALID -> "Der hinterlegte GitHub-Token ist abgelaufen oder hat keinen Zugriff mehr. Unten einen neuen Token eingeben."
    UpdateError.RATE_LIMITED -> "Zu viele Anfragen von diesem Netzwerk. In etwa einer Stunde erneut versuchen."
    UpdateError.NO_RELEASE -> "Unter github.com/${BuildConfig.UPDATE_REPO} ist kein Release sichtbar. Für das private Repository unten einen GitHub-Token hinterlegen."
    UpdateError.INVALID_METADATA, UpdateError.ASSET_MISSING -> "update.json oder APK fehlen oder sind ungültig. Es wird nichts installiert."
    UpdateError.MIN_SDK -> "Die neue Version benötigt eine neuere Fire-OS-Version."
    UpdateError.BLOCKED -> "Der Download sollte über einen nicht erlaubten Host laufen und wurde gestoppt."
    UpdateError.NO_SPACE -> "Auf dem Fire TV ist nicht genug Speicher frei."
    UpdateError.SIZE_MISMATCH, UpdateError.HASH_MISMATCH -> "Größe oder Prüfsumme stimmen nicht. Die Datei wurde gelöscht."
    UpdateError.WRONG_PACKAGE -> "Die heruntergeladene Datei ist keine Sofa-Hub-App und wurde gelöscht."
    UpdateError.SIGNATURE_MISMATCH -> "Die APK ist nicht mit dem Sofa-Hub-Schlüssel signiert und wurde abgelehnt."
    UpdateError.NOT_NEWER -> "Die angebotene Version ist nicht neuer als die installierte."
    UpdateError.INSTALL_FAILED -> "Fire TV hat die Installation abgelehnt. Alternativ per PC/ADB aktualisieren (siehe README)."
    UpdateError.INSTALL_ABORTED -> "Die Installation wurde abgebrochen. Du kannst es jederzeit erneut versuchen."
    UpdateError.DEBUG_BUILD -> "In-App-Updates gibt es nur für die signierte Release-Version."
}


/** Status und Eingabe des GitHub-Tokens für das private Update-Repository. */
@Composable
private fun GitHubTokenRow(vm: MainViewModel, version: Int, onChanged: () -> Unit, onEdit: () -> Unit) {
    val token = androidx.compose.runtime.remember(version) {
        (vm.c.githubTokens.readToken() as? de.sofahub.app.core.storage.TokenResult.Present)?.token
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Sh.veil(0.102f)))
    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt("GitHub-Token (optional)", Sh.style(23, FontWeight.SemiBold))
            Txt(
                token?.let { de.sofahub.app.core.storage.maskSecret(it) + " · gespeichert" } ?: "Nicht hinterlegt – nur für ein privates Repository nötig",
                Sh.style(20, color = if (token != null) Sh.text3 else Sh.warnText, mono = token != null),
            )
        }
        ShButton("gh-token", if (token == null) "Token eingeben" else "Ändern", height = 60, fontSize = 22) { onEdit() }
        if (token != null) ShButton("gh-clear", "Entfernen", height = 60, fontSize = 22, color = Sh.dangerText) {
            vm.c.githubTokens.clearToken()
            onChanged()
            vm.toast("GitHub-Token entfernt")
        }
    }
}

@Composable
private fun GitHubTokenDialog(vm: MainViewModel, onSaved: () -> Unit, onClose: () -> Unit) {
    run {
        var input by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        var error by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        de.sofahub.app.ui.components.ShDialog(
            "GitHub-Token eingeben",
            "Fine-grained-Token mit „Contents: Read-only“ nur für ${BuildConfig.UPDATE_REPO}. Bequemer: über „Einrichtung per Handy“ im PC-Browser einfügen.",
            ShIcon.Download, Sh.skyText, onDismiss = onClose,
        ) {
            de.sofahub.app.ui.components.FocusOnShow("gh-input")
            de.sofahub.app.ui.components.ShTextField("gh-input", "Token", input, { input = it.trim(); error = null }, placeholder = "github_pat_…", password = true, height = 68)
            error?.let { Txt(it, Sh.style(21, color = Sh.coralText), maxLines = 2) }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ShButton("gh-save", if (busy) "Prüfe …" else "Prüfen & speichern", primary = true, enabled = !busy) {
                    busy = true
                    scope.launch {
                        val (ok, message) = vm.c.saveGitHubToken(input)
                        busy = false
                        if (ok) {
                            onClose()
                            onSaved()
                            vm.toast("GitHub-Token gespeichert")
                        } else {
                            error = message
                        }
                    }
                }
                ShButton("gh-cancel", "Abbrechen") { onClose() }
            }
        }
    }
}
