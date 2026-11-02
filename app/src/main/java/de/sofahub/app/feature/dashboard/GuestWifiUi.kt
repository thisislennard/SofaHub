package de.sofahub.app.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.sofahub.app.core.model.GuestWifi
import de.sofahub.app.core.model.WifiSecurity
import de.sofahub.app.feature.MainViewModel
import de.sofahub.app.ui.components.FocusOnShow
import de.sofahub.app.ui.components.LocalFocusRegistry
import de.sofahub.app.ui.components.enterFirst
import de.sofahub.app.ui.components.QrCode
import de.sofahub.app.ui.components.ShButton
import de.sofahub.app.ui.components.ShDialog
import de.sofahub.app.ui.components.ShTextField
import de.sofahub.app.ui.components.Tile
import de.sofahub.app.ui.components.Txt
import de.sofahub.app.ui.theme.Icon
import de.sofahub.app.ui.theme.Sh
import de.sofahub.app.ui.theme.ShIcon

/** Kachel der Startseite: kleiner QR-Code mit WLAN-Name; OK zeigt ihn groß zum Scannen. */
@Composable
fun RowScope.GuestWifiTile(wifi: GuestWifi, onOpen: () -> Unit) {
    Tile("wifi", Modifier.weight(1f).fillMaxHeight(), onClick = onOpen) {
        Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            QrCode(wifi.qrContent(), 122, padding = 10, radius = 14)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(ShIcon.Wifi, Sh.teal, 24)
                    Txt("Gäste-WLAN", Sh.style(23, FontWeight.SemiBold))
                }
                Txt(wifi.ssid, Sh.style(20, color = Sh.text2), maxLines = 2)
                Txt("OK zum Scannen", Sh.style(17, color = Sh.text3))
            }
        }
    }
}

/** Großer QR-Code mit Name und Passwort – für Gäste, die nicht scannen können, steht beides lesbar daneben. */
@Composable
fun GuestWifiDialog(wifi: GuestWifi, onClose: () -> Unit) {
    ShDialog("Gäste-WLAN", "Mit der Handykamera scannen und verbinden", ShIcon.Wifi, onDismiss = onClose, width = 1000) {
        FocusOnShow("gw-close")
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
            QrCode(wifi.qrContent(), 420)
            Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("WLAN", Sh.style(20, color = Sh.hint))
                Txt(wifi.ssid, Sh.style(32, Sh.W650), maxLines = 2)
                if (wifi.security != WifiSecurity.NONE) {
                    Txt("Passwort", Sh.style(20, color = Sh.hint), Modifier.fillMaxWidth())
                    Txt(wifi.password, Sh.style(30, FontWeight.Medium, mono = true), maxLines = 3)
                } else {
                    Txt("Offenes WLAN, kein Passwort", Sh.style(22, color = Sh.text2))
                }
            }
        }
        ShButton("gw-close", "Schließen", primary = true) { onClose() }
    }
}

/** Gäste-WLAN am Fernseher eintragen (bequemer per Handy/PC-Browser unter „Per Handy einrichten“). */
@Composable
fun GuestWifiEditDialog(vm: MainViewModel, current: GuestWifi?, onClose: () -> Unit) {
    var ssid by remember { mutableStateOf(current?.ssid.orEmpty()) }
    var password by remember { mutableStateOf(current?.password.orEmpty()) }
    var security by remember { mutableStateOf(current?.security ?: WifiSecurity.WPA) }
    ShDialog("Gäste-WLAN", "Erscheint als QR-Code-Kachel auf der Startseite.", ShIcon.Wifi, onDismiss = onClose, width = 900) {
        FocusOnShow("gw-ssid")
        ShTextField("gw-ssid", "WLAN-Name", ssid, { ssid = it.take(32) }, height = 68, mono = false)
        if (security != WifiSecurity.NONE) ShTextField("gw-pw", "Passwort", password, { password = it.take(63) }, height = 68)
        Row(Modifier.enterFirst(LocalFocusRegistry.current, "gw-save"), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShButton("gw-save", "Speichern", primary = true) {
                val wifi = GuestWifi(ssid.trim(), if (security == WifiSecurity.NONE) "" else password, security)
                val problem = wifi.problem()
                if (problem != null) {
                    vm.toast(problem, warn = true)
                } else {
                    vm.updateSettings { it.copy(guestWifi = wifi) }
                    vm.toast("Gäste-WLAN gespeichert", wifi.ssid)
                    onClose()
                }
            }
            ShButton("gw-sec", security.label, icon = ShIcon.Sliders) {
                security = WifiSecurity.entries[(security.ordinal + 1) % WifiSecurity.entries.size]
            }
            ShButton("gw-cancel", "Abbrechen") { onClose() }
        }
    }
}
