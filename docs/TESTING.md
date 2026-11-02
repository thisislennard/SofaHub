# Tests

## Automatisiert

```powershell
.\gradlew.bat testDebugUnitTest lintDebug
```

Abgedeckt u. a.:

| Bereich | Testklasse | Was geprüft wird |
|---|---|---|
| Einstellungen | `SettingsCodecTest` | Rundreise, kein Token im JSON, Migration Schema 0→1, kaputtes Einzelfeld rettet den Rest, unbekannte Rollen (Downgrade) löschen nicht alle Rollen, Maskierung, Geheimnisfilter |
| HA-Adresse | `HaUrlTest` | Normalisierung, Zurückweisung von Zugangsdaten/Query/Fragment/Fremdprotokollen, ws/wss |
| HA-Logik | `HaServicesTest` | Helligkeit %↔0–255 inkl. Grenzen, 0 % = aus, explizites Ein/Aus, Szenen/Skripte nicht wiederholbar, Fähigkeiten nur aus Attributen (keine Namensraterei), Backoff-Grenzen |
| Snapshot/Events | `StateMergerTest` | gepufferte Events vor Snapshot, ältere Events verworfen, Löschung, Neuanlage, Idempotenz |
| HA REST | `HaRestClientTest` (MockWebServer) | Bearer-Token, 401/403/5xx/ungültige Antwort, Redirect wird nicht verfolgt, Netzwerkfehler, Service-Payload |
| HA WebSocket | `HaWebSocketSessionTest` (Fake-HA über WebSocket) | Handshake, Subscription vor `get_states`, eindeutige IDs, gepuffertes Event gewinnt, Registry → Räume, fehlende Rechte → manuell, `auth_invalid`, Server weg |
| Dashboard | `DashboardModelTest` | Rollen-Kacheln (bereit/fehlt/pausiert/ausstehend), Hausübersicht, Wetter, manuelle Räume, Einrichtungs-Auswahl belegt nur freie Rollen |
| Update-Vertrag | `ManifestValidatorTest`, `VerifyRulesTest` | Schema, applicationId, Tag, Assetname, Hash, Größe, fehlendes Asset, minSdk, Signatur/Version/Downgrade |
| GitHub | `GitHubReleaseClientTest` | 404, Ratelimit, Draft, Müll, kein Authorization-Header, Größenlimit update.json, https-/Host-Sperre |
| Download | `ApkDownloaderTest` | Hash ok, Hash falsch → keine Datei, zu groß → Abbruch, Abbruch → keine Halbdatei |
| Handy-Einrichtung | `PairingSecurityTest` | Geheimnis/Fragment, Host (DNS-Rebinding), Origin (CSRF), Sperre nach 5 Fehlversuchen, Ablauf |
| Bilder | `PhotoStoreTest` (Robolectric, Native Graphics, API 30) | Upload → verkleinert laden, Müll abweisen, gelöschte Datei → kein Absturz |

## Manuelle Prüfliste vor einem Release

1. Frische Installation der Release-APK, Einrichtung am TV (Adresse + Token), Geräte wählen.
2. Licht/Steckdose schalten, Szene starten, externe Änderung in HA erscheint ohne Neuladen.
3. HA kurz trennen (z. B. HA neu starten): Kacheln „— nicht verfügbar“, danach automatische Wiederverbindung.
4. Spiegeln öffnen, mit Sender verbinden, Zurück → Sofa Hub, HA wieder verbunden.
5. Streaming-App starten und zurückkehren: Fokus auf der zuletzt gewählten Kachel.
6. Update-Test mit zwei signierten Releases (siehe RELEASING.md).
7. `git grep` auf Token/Schlüssel, Release-Assets auf Debug-Signatur prüfen (das Asset-Skript bricht bei Debug-Schlüssel ab).

Emulator-Ergebnisse gelten nicht als Fire-TV-Test.
