# Architektur

Eine Activity, Jetpack Compose, ein ViewModel für Navigation und app-weite Aktionen. Integrationen liegen in Repositories
hinter Schnittstellen; Composables enthalten keine Zugangsdaten und keine Netzwerklogik.

```
Benutzeraktion → MainViewModel → Repository/Adapter → StateFlow → Compose-Oberfläche
```

## Packages (`app/src/main/java/de/sofahub/app`)

| Package | Inhalt |
|---|---|
| `core/model` | `AppSettings` (Rollen, Favoriten, Fotos, Hintergrund, Notiz …), HA-Zustände, Fehlerarten |
| `core/storage` | `SettingsCodec` (JSON + Migrationen + feldweise Rettung), `DataStoreSettingsRepository`, `KeystoreSecretStore` |
| `core/network` | OkHttp-Basis, `HttpsOnlyInterceptor`, `RedirectInterceptor` (nur GitHub-Hosts), `Redactor` |
| `integration/homeassistant` | `HaUrl`, `HaRestClient`, `HaWebSocketSession`, `StateMerger`, `HaServices`/`EntityView`, `RealHomeAssistantRepository`, `DemoHomeAssistantRepository` |
| `integration/apps` | Startbare TV-Apps (Leanback/Launcher-Queries), Start mit Fehlerarten |
| `integration/miracast` | `FireTvMiracastController` (Fähigkeit, Start, Ausweichweg, Gerätename) |
| `integration/updates` | `GitHubReleaseClient`, `ManifestValidator`, `ApkDownloader`, `ApkVerifier`/`VerifyRules`, `InstallerAdapter`, `UpdateRepository` |
| `integration/pairing` | `PairingSecurity`, `PairingServer` (Minimal-HTTP + Einrichtungsseite) |
| `integration/photos` | `PhotoStore` (Upload, Picker-URIs, Laden mit Sampling/EXIF/Cache) |
| `feature/*` | Ansichten: Einrichtung, Start (Dashboard), Smart Home, Spiegeln, Favoriten, Einstellungen, Auswahl, Updates, Diashow |
| `ui/*` | Design-Tokens, Icons, `Tile` (Fokus/OK/OK-lang), Insel, Buttons, Dialoge, Textfeld, QR |

`AppContainer` (in `SofaHubApp.kt`) erzeugt alle Instanzen einmal pro Prozess.

## Zustände

- **Start:** Einstellungen laden (`null` = Ladeansicht mit Platzhaltern) → `setupCompleted` ? Startseite : Einrichtung.
- **HA-Verbindung:** `NotConfigured · Connecting · Connected · Retrying(error, nächster Versuch) · AuthFailed · TokenUnreadable · Disconnected`.
  Pro Entität `normal / unknown / unavailable`, gelöschte Entitäten separat, Cache als `stale` markiert.
- **Aktionen:** `Pending → Acknowledged | Failed` – „quittiert“ heißt nicht, dass das Gerät den Zustand schon erreicht hat.
- **Updates:** `Idle → Checking → UpToDate | Available | Error → Downloading → Verifying → AwaitingPermission | ReadyToInstall → InstallerLaunched`,
  nach Neustart `Installed`. Ein geprüftes Angebot überlebt den Prozess-Neustart (`cache/updates/offer.json`).
- **Handy-Einrichtung:** `Idle → Waiting → AwaitingApproval → Approved → Finished | Expired | Locked | Failed`.

## Lebenszyklus

- `ProcessLifecycleOwner`: Vordergrund → HA verbinden; Hintergrund → Socket schließen, Einrichtungsdienst stoppen.
- `MainActivity.onResume` (Rückkehr aus Streaming-App/Spiegeln/Einstellungen): installierte Apps, Spiegel-Fähigkeit und
  Installationsfreigabe neu prüfen.
- Keine Dauer-Services, keine Wake Locks, keine Hintergrund-Jobs.

## Navigation und Fokus

Eigener Back-Stack im ViewModel (`Route`). Jede Ansicht hat eine `FocusRegistry`, die das zuletzt fokussierte Element
merkt; „Zurück“ stellt den Fokus wieder her. Dialoge halten den Fokus (`focusProperties { onExit = cancel }`) und werden mit
Zurück geschlossen. Zurück auf der Startseite verlässt Sofa Hub (kein Launcher-Ersatz).
