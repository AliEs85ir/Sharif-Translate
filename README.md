# Sharif Translate

Developed and maintained by **AliEs85ir (Ali Esameili | Sharif comp)**.

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

`shadowJar` alone updates `app/build/libs/SharifTranslate.jar`; the EXE loads the separate JAR under `app/build/windows/Sharif Translate/app/`. `refreshWindowsApp` updates that copy and all three bundled plugin JARs while preserving settings and history. A changed plugin fingerprint may require **Accept Update** in the Plugins page; this keeps the plugin settings. Use `windowsImage` for a complete rebuild including runtime and plugins; it replaces the generated installation directory.

Icon settings are created on launch at `app/build/windows/Sharif Translate/icons/icons.json`, with a Persian text guide beside them. Restart the application after editing these settings.

## Project independence

No upstream repository or update source is configured. Automatic update checks default to off. To use your own GitHub Releases, provide both JVM properties `shariftranslate.update.owner` and `shariftranslate.update.repository`. Manual checks report an unconfigured source without network access when either property is absent. Configure project contacts and maintainers in this repository when available.

Google and Bing retain their translation endpoints. AI retains its configurable endpoint, credentials, models and custom headers; default headers identify Sharif Translate without an inherited repository URL. The plugin API namespace is `org.shariftranslate`; external plugins must be rebuilt for this API. Bundled plugins are built and packaged together.

Bundled plugin maintenance is credited to **Ali Esmaeili**. See [plugin changes](plugins/CHANGES.md) and [plugin attribution](plugins/NOTICE.md). AI uses the user's own key and a user-selected model; new installations do not preselect a potentially unavailable model.

The supplied logo and all generated icon sizes are in `ui-swing/src/main/resources/icons/app/`.

See [architecture](wiki/Architecture.md), [plugin development](wiki/Creating-a-Plugin.md) and the bundled wiki for further documentation.

## Attribution

Sharif Translate is the independently named and maintained project developed by AliEs85ir (Ali Esameili | Sharif comp), who is responsible for its completion, enhancements, and ongoing development under the Sharif Translate identity.

The original application codebase was written by Ahmed Hatem as QTranslate. Sharif Translate builds on that foundation; AliEs85ir holds the rights to their own contributions and modifications, while the rights to the original code remain with its respective copyright holder. The original MIT copyright notice and applicable third-party license notices are retained in [LICENSE](LICENSE) and bundled resources. This attribution records the project's origin and does not configure a remote, update source, or external service.
