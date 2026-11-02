# Kompatibilität

Gemessen, nicht angenommen. Nicht getestete Kombinationen sind ausdrücklich als **OFFEN** markiert.

## Testgerät

| Wert | Gemessen am 31.10.2026 (per `adb shell getprop` / `wm`) |
|---|---|
| Modell | AFTKRT (Fire TV Stick 4K Max, 2. Generation, Codename `karat`) |
| Fire OS | 8.1.6.0 (RS8160/3380), `ro.build.version.fireos=8.0` |
| Android / API | 11 / **API 30** |
| ABI | **armeabi-v7a** (32 Bit), `armeabi` |
| Anzeige | logisch 1920×1080 bei 320 dpi (physisch 3840×2160) → 960×540 dp |

Sofa Hub ist reines Kotlin (keine nativen Bibliotheken) – die universelle APK läuft daher auf armeabi-v7a wie auf arm64.

## Matrix

| Funktion | Ergebnis auf AFTKRT / Fire OS 8.1.6.0 | Datum |
|---|---|---|
| Installation per ADB, Start aus App-Bibliothek | ✅ Debug- und Release-APK | 31.10.2026 |
| Bedienung nur mit Fernbedienung (D-Pad/OK/Zurück) | ✅ alle Ansichten (Einrichtung, Start, Einstellungen, Updates, Favoriten) | 31.10.2026 |
| Spiegeln: Direktaufruf aus der App | ✅ `com.amazon.cast.sink/.DisplayMirroringSinkActivity` wird gestartet (Log: `START … from uid` der App) | 31.10.2026 |
| Spiegeln: Rückkehr zu Sofa Hub | ✅ Zurück-Taste beendet den Empfang, Sofa Hub ist wieder vorne | 31.10.2026 |
| Spiegeln: echte Verbindung mit Sender (Windows Win+K / Android) | **OFFEN** – noch kein Sender getestet | – |
| In-App-Installation (PackageInstaller-Session) | ✅ Upgrade versionCode 1 → 2 mit Systembestätigung, Einstellungen identisch | 31.10.2026 |
| Installation abbrechen | ✅ `STATUS_FAILURE_ABORTED` → „Installation abgebrochen“, erneuter Versuch möglich | 31.10.2026 |
| Freigabe „Unbekannte Apps installieren“ | ✅ Aufruf landet in *Entwickleroptionen › Unbekannte Apps installieren*; Liste zeigt nur Sofa Hub | 31.10.2026 |
| GitHub-Update (Download aus Release) | ✅ In-App-Update 1.0.0 → 1.0.1 mit Prüfung, Systemdialog, Einstellungen erhalten | 01.11.2026 |
| Handy-Einrichtung (QR, Freigabe am TV, Notiz, Bild-Upload, Ende) | ✅ inkl. Abweisung von falschem Host, fehlendem Geheimnis, fremder Origin | 31.10.2026 |
| Eigenes Hintergrundbild | ✅ per Handy-Upload, abgedunkelt dargestellt | 31.10.2026 |
| Dateiauswahl für Bilder (`OPEN_DOCUMENT`/`GET_CONTENT`) | ❌ nicht vorhanden – Bilder nur per Handy | 31.10.2026 |
| Home Assistant erreichbar + automatisch gefunden | ✅ per Zeroconf gefunden, falscher Token → „Token ungültig“ | 31.10.2026 |
| Home Assistant mit echtem Token | ✅ Wetter, Temperatur, Räume aus der Registry, Geräte, Licht geschaltet | 31.10.2026 |
| Automationen (Bildschirm aus/an, Vordergrund-App) | Gebaut, installiert; Auslösen mit echten Geräten noch nicht getestet | 31.10.2026 |
| Kiosk-Modus (Bedienungshilfe-Dienst) | ✅ Home → Amazon-Startbildschirm → nach ~0,5 s Sofa Hub (auch aus YouTube); Neustart des Sticks noch nicht getestet | 31.10.2026 |
| App-Logos der Favoriten | ✅ TV-Banner per PackageManager (Netflix, MagentaSport, YouTube, Spotify, Twitch, Prime Video) | 31.10.2026 |
| Systemtastatur | ✅ öffnet erst nach OK im Feld; Fire OS bietet zusätzlich eigene Handy-Eingabe per QR an | 31.10.2026 |

## Fire-OS-Besonderheiten (gemessen)

- **Kein fremder Startbildschirm.** Amazons „Custom Home“ (`com.amazon.tv.action.CUSTOM_HOME`) ist durch
  `signature|amazon` geschützt; ein Bedienungshilfe-Menü für Fremd-Apps fehlt (Aktivierung nur per ADB). Force-Stop einer App
  entfernt ihren Bedienungshilfe-Dienst aus der Liste (Android-Standard). Sideloaded Apps zeigt der Amazon-Startbildschirm als
  Icon im grauen Rahmen (kein Banner); das Icon wird zwischengespeichert.
- **Keine `.local`-Namen.** `ping homeassistant.local` → „unknown host“ (Android 11 hat keinen mDNS-Resolver für Apps).
  Sofa Hub findet Home Assistant deshalb über die Zeroconf-Ankündigung `_home-assistant._tcp` (NsdManager) und trägt
  die IP ein (Anzeige z. B. „Home · 192.168.178.20:8123“). Eine `.local`-Adresse ergibt einen eigenen Hinweis.

- **Spiegelungsempfang schaltet das Netzwerk um.** Solange `DisplayMirroringSinkActivity` offen ist, deaktiviert Fire OS
  das Ethernet-Interface (Log `CdsMirroring: … Ethernet interface has been Enabled` beim Beenden). ADB über Netzwerk und die
  HA-Verbindung brechen dabei ab. Sofa Hub baut die HA-Verbindung bei Rückkehr automatisch neu auf.
- **Die Spiegel-Activity ist intern.** Sie ist exportiert und nicht durch eine Berechtigung geschützt (Manifest per `aapt2`
  geprüft), aber nicht dokumentiert. Ein Fire-OS-Update kann sie umbenennen oder schützen. Sofa Hub prüft vor jedem Start,
  ob sie existiert und exportiert ist, und zeigt sonst den Ausweichweg (Home-Taste lang › Spiegeln, oder *Display & Sounds*).
- `Settings.ACTION_CAST_SETTINGS` ist nicht vorhanden; `ACTION_WIFI_DISPLAY_SETTINGS` führt in die Netzwerkeinstellungen
  (nicht nutzbar); `ACTION_DISPLAY_SETTINGS` öffnet *Display & Sounds* (Ausweichweg).
- **Freigabe zum Installieren beendet die App.** Wie unter Android üblich wird der Prozess beim Erteilen beendet. Sofa Hub
  sichert das geprüfte Update-Angebot und stellt es nach dem Neustart wieder her (kein erneuter Download).
- **Nach der Installation startet Sofa Hub nicht selbst neu.** Fire OS zeigt die zuvor geöffnete App; Sofa Hub muss erneut
  geöffnet werden und bestätigt dann das Update.

## Sender (Miracast)

Der Sender muss Miracast unterstützen. Windows 10/11 mit Miracast-fähigem WLAN-Adapter: Win+K. Android: herstellerabhängig
(„Smart View“, „Bildschirm übertragen“); Pixel-Geräte und iPhones unterstützen kein Miracast. Keine dieser Kombinationen ist
bisher getestet.
