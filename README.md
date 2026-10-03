# Sharif Translate

![Application icon](docs/images/app-icon.png)

Independent desktop translation application with Google, Bing and configurable AI services, OCR, speech, dictionaries, history, collections, themes and multilingual UI.

## Build and run on Windows

The build uses JDK 17 for Gradle build logic and JDK 21 for compilation. Java 21 is used for the portable distribution. Run:

```powershell
.\gradlew.bat test :app:windowsZip :app:artifactSmokeJar --no-daemon --no-configuration-cache
& ".\app\build\windows\Sharif Translate\Sharif Translate.exe"
```

The portable executable includes its Java runtime. The ZIP is in `app/build/distributions/`. Persistent data is stored beside the installation when writable, otherwise in the platform configuration directory under `Sharif Translate`. Existing installations are left intact. `-DappData=...` overrides the data directory. The default single-instance port is 49186, independent of the original application.

To update application code or UI icons in an existing Windows build, fully exit the app (including its tray icon), then run:

```powershell
.\gradlew.bat :app:refreshWindowsApp
& ".\app\build\windows\Sharif Translate\Sharif Translate.exe"
```

`shadowJar` alone updates `app/build/libs/SharifTranslate.jar`; the EXE loads the separate JAR under `app/build/windows/Sharif Translate/app/`. `refreshWindowsApp` updates that copy while preserving settings and history. Use `windowsImage` for a complete rebuild including runtime and plugins; it replaces the generated installation directory.

Icon settings are created on launch at `app/build/windows/Sharif Translate/icons/icons.json`, with a Persian text guide beside them. Restart the application after editing these settings.

## Project independence

No upstream repository or update source is configured. Automatic update checks default to off. To use your own GitHub Releases, provide both JVM properties `shariftranslate.update.owner` and `shariftranslate.update.repository`. Manual checks report an unconfigured source without network access when either property is absent. Configure project contacts and maintainers in this repository when available.

Google and Bing retain their translation endpoints. AI retains its configurable endpoint, credentials, models and custom headers; default headers identify Sharif Translate without an inherited repository URL. The plugin API namespace is `org.shariftranslate`; external plugins must be rebuilt for this API. Bundled plugins are built and packaged together.

The supplied logo and all generated icon sizes are in `ui-swing/src/main/resources/icons/app/`.

See [architecture](wiki/Architecture.md), [plugin development](wiki/Creating-a-Plugin.md) and the bundled wiki for further documentation.

## Attribution

Derived from QTranslate by Ahmed Hatem. Original MIT copyright and third-party licenses are retained in [LICENSE](LICENSE) and bundled resources. Attribution is historical and does not configure a remote, update source or external service.
