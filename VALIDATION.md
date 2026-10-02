# Sharif Translate validation

Validated on Windows on 2026-10-03.

- Build: `test :app:windowsZip :app:artifactSmokeJar`, with the installed JDK 17 and provisioned JDK 21, offline dependency resolution, configuration cache disabled and Kotlin compilation in process. Build succeeded.
- Automated test reports: 22 tests, 0 failures. Coverage includes HTTP errors/cancellation, AI payloads, settings, persistence, collections, translation state, Persian/RTL, Unicode wrapping, single instance and missing update configuration.
- Packaged runtime and JAR: three bundled plugins loaded. Live Google translations passed for English/Persian, automatic language detection, multiline text and Unicode. Live Bing translation passed. Translation history undo/redo passed.
- AI services passed over controlled local HTTP: translation, summary, rewrite, dictionary, spell checking and vision OCR, plus rate limit, malformed/truncated response and connection refusal. A paid/provider AI account was not exercised; no API credentials were supplied.
- Favorites, collections and settings persisted and reloaded in isolated validation directories.
- Actual application entry point from the packaged JAR rendered successfully with the packaged Java runtime. Window title/icons and Tray branding passed. Render saved in `app/build/validation/ui.png`.
- Native `Sharif Translate.exe` was launched separately and remained running. Its extracted Windows icon matches the supplied logo.
- ZIP contains only the `Sharif Translate/` application tree, with no old application folder. Packaged JAR has no original application package namespace. Icon resources include all supplied sizes and the native ICO.
- No Git remote is configured. Active source/configuration contains no inherited repository, donation-account or update endpoint. Historical attribution, MIT copyright and original changelog are retained. Provider endpoints and third-party license URLs remain intentional.

Outputs: `app/build/windows/Sharif Translate/Sharif Translate.exe` and `app/build/distributions/SharifTranslate-1.2.1-windows-x64.zip`.
