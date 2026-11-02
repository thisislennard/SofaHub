# Releases und Signierung

## Signaturschlüssel

Alle Releases müssen mit **demselben** Schlüssel signiert sein – sonst kann Android die vorhandene Installation nicht
aktualisieren (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) und nur Deinstallieren hilft, wobei alle Einstellungen verloren gehen.

- Schlüssel: `C:\Users\thisi\.sofahub-signing\sofahub-release.jks` (PKCS12, RSA 4096, Alias `sofahub`, angelegt 31.10.2026)
- Passwörter: `keystore.properties` im Projekt (gitignored) und Kopie `C:\Users\thisi\.sofahub-signing\keystore.properties.backup`
- Zertifikat SHA-256: `153124d90acacd6b6b7fa8de553cf2a5f809d4c753823818152bf2333490bb5b`

**Sicherung:** Ordner `.sofahub-signing` an zwei getrennten Orten sichern (z. B. Passwortmanager + verschlüsselter
USB-Stick). Ohne Schlüssel sind keine Updates bestehender Installationen möglich.

Debug-Builds (`de.sofahub.app.debug`) nutzen den SDK-Debug-Schlüssel und sind eine eigene App; der Updater akzeptiert nur
Release-APKs mit der Kennung `de.sofahub.app` und dem obigen Zertifikat.

## Version

In `gradle.properties`: `sofahub.versionName` (SemVer) und `sofahub.versionCode` (bei jedem Release erhöhen, nie zurück).
Der Tag lautet `v<versionName>`. Release Notes: `docs/release-notes/v<versionName>.md` (deutsch, kurze Punkte – die App
zeigt sie auf der Update-Seite).

## Veröffentlichen (Standardweg, lokal)

```powershell
# 1. gradle.properties + docs/release-notes/v1.2.0.md anpassen, committen, pushen
git commit -am "Release 1.2.0"; git push
# 2. bauen, prüfen, Tag setzen, Release anlegen
python scripts\publish_release.py
```

Das Skript bricht ab, wenn das Arbeitsverzeichnis nicht sauber oder nicht gepusht ist, führt Tests und Lint aus, baut die
signierte APK, prüft Signatur/Paket/Version (kein Debug-Schlüssel) und erzeugt `SofaHub-v<ver>-universal.apk`,
`update.json` und `SHA256SUMS`. Erst nach Rückfrage setzt es den Tag und legt das Release an – zuerst als Entwurf mit allen
Dateien, dann veröffentlicht. Der Signaturschlüssel verlässt dabei nie den PC.

Alternative: Workflow `release.yml` (nur manuell, *Actions › Release › Run workflow*), wenn die Signing-Secrets in GitHub
hinterlegt sind (`SOFAHUB_KEYSTORE_BASE64`, `SOFAHUB_KEYSTORE_PASSWORD`, `SOFAHUB_KEY_ALIAS`, `SOFAHUB_KEY_PASSWORD`,
Umgebung `release`).

## Wie die App Updates findet

Die App fragt `https://api.github.com/repos/thisislennard/SofaHub/releases/latest` ab – ohne Konto oder Token. Ein
optionaler, nur lesender Fine-grained-Token (*Einstellungen › Updates › Token eingeben*) wird nur für ein privates
Repository (z. B. einen eigenen Fork) gebraucht. Er wird verschlüsselt im Android Keystore gespeichert, geht
ausschließlich an `api.github.com`, und die Weiterleitung zum Download-Speicher erhält ihn nicht.

## Update-Test

1. Release A per ADB installieren (oder aus der App) und einrichten.
2. Release B mit höherer versionCode veröffentlichen.
3. Sofa Hub: *Einstellungen › Updates › Jetzt prüfen → Herunterladen → (Freigabe) → Jetzt installieren* → Fire-TV-Dialog
   *Installieren* → Sofa Hub öffnen → „Update installiert“. Einstellungen bleiben erhalten.
