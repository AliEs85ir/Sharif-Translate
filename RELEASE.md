# Release preparation

A GitHub Release is not required for development. The only permanent branch is `main`. The current version is set in `gradle.properties` as `appVersion`; `:app:verifyAppVersion` checks the UI value in `AppConstants.kt`. The Windows ZIP and EXE take their versions from Gradle.

## Before publishing

1. Choose the next semantic version and update `appVersion` and `AppConstants.APP_VERSION` together. Add a Sharif Translate entry to `CHANGELOG.md`. Keep plugin and API versions separate unless their interfaces change.
2. Merge the change into `main` after CI passes. Build on a fresh Windows checkout using `./gradlew.bat test :app:windowsZip --no-daemon --no-configuration-cache`. The `windowsImage` task replaces its generated image, so do not run it over an installation containing personal data.
3. Open the ZIP from `app/build/distributions/`. Confirm it contains the EXE, bundled runtime, bundled plugin JARs, languages, themes, wiki, README and LICENSE. Confirm it contains no settings, history, collections, credentials, logs, or private keys.
4. Run the EXE from the extracted ZIP using a fresh data directory. Check the icon, version, translation, speech, dictionary, settings persistence, and bundled plugin loading. AI cloud behavior requires a user-supplied key and model; never bundle a developer key.
5. Confirm the version shown in the UI and Windows metadata matches the ZIP filename and prospective tag `vX.Y.Z`. Review the public attribution and preserved MIT/third-party notices.
6. Push tag `vX.Y.Z` only when ready to publish. The tag triggers `.github/workflows/release.yml`; it builds and publishes a GitHub Release with the Windows ZIP. Verify the Release assets and checksum before enabling update checks in the app.

For a packaging rehearsal without a release, run the Release workflow manually from GitHub Actions on `main`. It uploads a temporary workflow Artifact and does not publish a Release.

## Update source

The updater uses GitHub's latest release endpoint. Leave automatic update checks off until the first tested Release exists. After publication, configure `shariftranslate.update.owner=AliEs85ir` and `shariftranslate.update.repository=Sharif-Translate` in the JVM launch options, then test both the no-update and new-update paths.

## API and plugin distribution

The `:api` module supports a local Maven publication. External registry credentials and a public distribution policy have not been configured. Third-party plugin authors can build against the documented API and install JARs locally. Do not claim a hosted plugin marketplace or published API package until its artifact endpoint and compatibility policy are verified.
