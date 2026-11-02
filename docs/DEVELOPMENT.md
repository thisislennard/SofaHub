# Entwicklung (Windows)

## Voraussetzungen

- JDK 17 (z. B. Microsoft Build of OpenJDK 17), `JAVA_HOME` gesetzt
- Android SDK mit Platform 37 und Build-Tools 36 (Android Studio oder `sdkmanager`)
- `local.properties` mit `sdk.dir=C\:\\Android\\Sdk` (wird von Android Studio angelegt, nicht einchecken)
- Python 3 nur für `scripts/make_release_assets.py`

Keine globale Gradle-Installation nötig – immer den Wrapper verwenden.

## Befehle

```powershell
.\gradlew.bat assembleDebug          # app\build\outputs\apk\debug\app-debug.apk  (de.sofahub.app.debug)
.\gradlew.bat assembleRelease        # app\build\outputs\apk\release\app-release.apk (signiert, wenn keystore.properties existiert)
.\gradlew.bat testDebugUnitTest      # Unit-Tests
.\gradlew.bat lintDebug              # Lint
```

## Auf dem Fire TV ausprobieren

```powershell
adb connect 192.168.178.50:5555                      # Beispiel-IP
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n de.sofahub.app.debug/de.sofahub.app.MainActivity
adb exec-out screencap -p > screen.png
```

Hinweis: Während der Spiegelungsempfang offen ist, trennt Fire OS die Netzwerkverbindung – auch ADB.

## Debug-Hilfen

- **Demo-Modus** (Einrichtung oder Einstellungen › Home Assistant): Beispielgeräte, überall als „Demo“ gekennzeichnet.
- **Installer-Test** (nur Debug, Einstellungen › App-Informationen): installiert `files/installer-test.apk` derselben
  Debug-App mit höherer versionCode über den echten Update-Weg:

  ```powershell
  .\gradlew.bat assembleDebug -Psofahub.versionCode=2 -Psofahub.versionName=0.1.1
  copy app\build\outputs\apk\debug\app-debug.apk B.apk
  .\gradlew.bat assembleDebug
  adb install -r app\build\outputs\apk\debug\app-debug.apk
  adb push B.apk /data/local/tmp/b.apk
  adb shell "run-as de.sofahub.app.debug sh -c 'cat /data/local/tmp/b.apk > files/installer-test.apk'"
  ```

## Design

Alle Design-Werte (Farben, Abstände, Schrift) stehen in `ui/theme/Theme.kt`; 1 dp entspricht einem
Design-Pixel der 1920×1080-Basis. Schrift: Geist (variabel, `res/font`).
