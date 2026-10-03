# Sharif Translate system audit — 2026-10-03

## Scope and changes

The audit covered translation state and cancellation, HTTP and AI response handling,
plugin/storage lifecycle, audio ownership, Swing layouts/dialogs/RTL, supplied icons,
and the Windows distribution. Existing behavior was retained where valid.

Each fix was built and tested before its topic commit:

- `8bac985` Translation: reject empty results, honor explicit source language, retain primary translation if supplementary output fails, cancel obsolete requests, recover OCR/spelling errors.
- `a69abc9` Infrastructure: close plugin JARs/classloaders, validate manifests before construction, shut down uninstalled plugins, protect filename collisions, retain DataStore ownership on reset.
- `241d85f` Network/AI: avoid retrying billable POST requests, accept successful 2xx responses, handle header case, preserve cancellation, validate endpoint/header configuration and translated content.
- `03486b5` UI: usable output height, preserved split position, correct extra-output visibility, immediate layout/shortcut binding, screen-bounded windows and small-screen popup placement.
- `51f19c1` Assets: 48 supplied PNGs (24 light/dark pairs), theme-aware scalable rendering, thread-safe icon cache, safe popup copy feedback.
- `27bcdd3` UI: readable history/settings labels and synchronous language-control state on the UI thread.
- `d2db2e9` Audio: replacing playback cannot close the new decoder; closing the player does not cancel the application's shared coroutine scope.
- `8176752` RTL: document direction follows its content independently of interface direction; newly selected settings pages inherit dialog orientation.
- `8fb0f3e`, `461bc5f` Additional OCR/spelling recovery, AI concurrency/Unicode/cancellation regressions.

## Final build and verification

Full `build :app:windowsZip :app:artifactSmokeJar` succeeded with offline dependencies,
configuration cache disabled, JDK 17 running Gradle and the configured JDK 21 toolchain.
The test suite reports **51 tests, zero failures, errors or skips**.

The shipped Java runtime and application JAR passed:

- Live Google English/Persian translation, automatic language detection, multiline Unicode/emoji input, reverse direction; live Bing translation.
- Controlled local HTTP AI translation, summary, rewrite, dictionary, spelling and vision OCR; rate limiting, malformed/truncated responses and refused connection.
- History undo/redo, settings reload, favorites and collections persistence.
- Real Swing input/button/result flow at 100%, 125%, 150% and 200% application scale; English/light and Persian/dark interfaces.
- All three main layouts, extra-output visibility, all eight settings pages, History, Collections, Dictionary and About dialogs at each scale. Captured renders were inspected, including Persian/English direction and clipped-label regressions.

The UI smoke test asserts that production classes come from the packaged JAR, records
uncaught UI exceptions, and requires an explicit completion marker. Test data is isolated
under `app/build/validation/`; personal configuration/history was not used.

All 48 source PNGs were byte/hash compared to `E:\Save Fotage\Icon\my icon\PNG`
and to their packaged resources. Provenance is in
`ui-swing/src/main/resources/icons/supplied/sources.json`.
ZIP CRC, root paths, and extracted JAR/EXE byte equivalence passed.

The final native EXE was launched with isolated data. Its visible input/loading/output
flow completed a live translation. During this run the primary Google endpoint failed;
the fallback succeeded and the UI returned to its ready state (native runtime log).
The application was left open for the user.

The external-process shortcut/popup smoke test passed with the desktop idle: native
dispatch 5 ms, selected-text capture 401 ms cold / 323 ms warm; clipboard restoration,
loading/idle/focus/menu behavior, external click with hotkeys disabled, pin/unpin,
empty selection and dictionary dismissal all passed. Earlier runs were interrupted by
simultaneous desktop input and failed to capture selection; they are retained as diagnostic
logs and are not counted as successful runs. A diagnostic fixture key listener confirmed
Ctrl+C delivery during the successful run; that temporary instrumentation was removed.

Final ZIP SHA-256:
`7717a0ecf02ecb88cdc642c5f6dac4ff526871b04b383cb950e583197510458d`.

## Reproduction and evidence

Build in PowerShell:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.20+8'
.\gradlew.bat build :app:windowsZip :app:artifactSmokeJar --offline --no-daemon --no-configuration-cache '-Pkotlin.compiler.execution.strategy=in-process'
```

Run `app/src/test/resources/audit-ai-server.py` locally for controlled AI scenarios.
The fixture listens only on `127.0.0.1:18765`, contains no credentials, and does not
represent a real provider's model quality.

```powershell
$java='.\app\build\windows\Sharif Translate\runtime\bin\java.exe'
$cp='app\build\windows\Sharif Translate\app\SharifTranslate.jar;app\build\validation\artifact-smoke.jar'
& $java -cp $cp org.shariftranslate.app.ArtifactSmokeKt 'app/build/validation/recheck-network' 'http://127.0.0.1:18765/v1'
& $java -cp $cp org.shariftranslate.app.SystemAuditUiSmoke 'app/build/validation/recheck-ui150' 150 fa-IR
& $java -cp $cp org.shariftranslate.app.ShortcutUiSmoke 'app/build/validation/recheck-shortcuts'
```

The explicit desktop tests open windows and the shortcut test injects input into its
own external fixture. Run desktop tests sequentially without interacting with their windows.
Local evidence (generated and Git-ignored):

- `build/audit-rtl-build.log`: full Windows package build.
- `build/audit-final-build.log`: final source/tests build after removing temporary diagnostics.
- `build/audit-final-idle-shortcuts.log`: successful external-process shortcut/popup test.
- `build/audit-final-native.log`: native EXE startup and live translation/fallback.
- `build/audit-final-network.log`: packaged translation/network/persistence test.
- `build/audit-final-ui{100,125,150,200}.log`: packaged UI runs.
- `app/build/validation/final-ui{100,125,150,200}/*.png`: rendered windows/pages.
- Gradle XML reports in each module's `build/test-results/test/`.

## Limits

No real AI provider credentials were supplied: remote account authentication, billing,
model availability and model quality remain unverified. AI protocol/error tests use a local
HTTP fixture. Real Google/Bing network paths were exercised. Audio lifecycle uses controlled
decoders; physical speaker output was not certified. Application scaling and synthetic
small/negative-coordinate screen geometry were tested; physical multi-monitor DPI transitions
were not. These results describe tested scenarios, not a guarantee against every possible defect.

Windows output: `app/build/windows/Sharif Translate/Sharif Translate.exe`.
Distribution: `app/build/distributions/SharifTranslate-1.2.1-windows-x64.zip`.
