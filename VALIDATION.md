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

## Shortcut and popup fixes â€” 2026-10-03

- Rebuilt with `test :app:windowsZip :app:artifactSmokeJar`. Current suite: 36 tests, 0 failures, including 14 new shortcut regression tests.
- Translation and dictionary popups no longer hide on idle, mouse exit, focus loss or Escape. Native outside mouse presses dismiss unpinned popups, including presses in other processes and while global hotkeys are disabled. Explicit close controls and pinning remain available. Menu and owned-window clicks count as inside clicks.
- Removed obsolete idle-timeout controls. Serialized timeout fields remain for configuration compatibility and no longer control dismissal.
- Windows global hotkeys now use a blocking Win32 message loop instead of jKeymaster's 300 ms polling loop. Registration completes before returning, and held keys do not repeatedly dispatch. Native dispatch measured 5 ms locally, excluding selection capture, UI rendering and network translation.
- Selection capture waits for shortcut modifiers to be released, copies once, polls for fresh text, and restores clipboard contents before dispatching the action. It preserves Unicode/whitespace and non-text clipboard formats; empty selections never reuse stale clipboard text. Slow source applications have a 750 ms polling budget. Cancellation and transient clipboard contention are covered.
- Double-Ctrl requires standalone taps; Ctrl+C and other chords do not trigger it. Enable/disable settings, global focus actions, local/global scope changes and preserving other Swing key mappings are corrected.
- `ShortcutUiSmoke` passed against the packaged JAR and runtime with an external JVM as the source application. Checks cover slow loading, idle, focus changes, inside/menu clicks, external clicks, disabled hotkeys, reopen, pin/unpin, empty selection, clipboard restoration and dictionary dismissal. Selection capture measured 446 ms cold and 267 ms warm, including automated key injection; the empty-selection path took about 1 second including injection and its polling budget.
- The actual application entry point also passed Swing rendering, window title/icon and tray checks with the packaged runtime. `ArtifactUiSmoke` accepts `-Dshariftranslate.uiSmokePolls=480` for a 120-second startup budget on Windows environments with slow installed-font scanning.

Run the explicit Windows desktop smoke test from the repository root:

```powershell
& ".\app\build\windows\Sharif Translate\runtime\bin\java.exe" -cp "app\build\windows\Sharif Translate\app\SharifTranslate.jar;app\build\validation\artifact-smoke.jar" org.shariftranslate.app.ShortcutUiSmoke "app\build\validation\shortcut-validation"
```

The smoke test opens temporary test windows and sends keyboard/mouse input. Its captured log is in `app/build/validation/shortcut-smoke.log`.


## Full system audit — final verification, 2026-10-03

See [SYSTEM_AUDIT.md](SYSTEM_AUDIT.md) for fixes, topic commits, reproduction commands,
evidence and unverified scope. The expanded suite has 51 tests with no failures/errors/skips.
The final Windows distribution passed live Google/Bing and controlled AI protocol/error
checks, persistence checks, and real Swing UI flows at 100/125/150/200% application scale.
All three layouts, eight settings pages and principal dialogs were opened in English/light
and Persian/dark configurations. The 48 supplied PNG resources match their original files
and packaged copies. The external-process shortcut/popup test passed with an idle desktop;
interfered runs are documented separately. The native EXE launched and displayed a successful
live translation, including recovery through Google's fallback endpoint.

Real remote AI provider credentials and physical multi-monitor DPI/audio hardware remain
outside the verified scope. This supersedes earlier test counts above.
