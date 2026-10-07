# Plugin improvements — 2026-10-07

Maintainer displayed in all three plugin manifests: **Ali Esmaeili**.
Google/Bing versions: **1.1.0**. AI version: **2.1.0**.
Plugin IDs, service IDs, settings keys and minimum API version are preserved.
See [NOTICE.md](NOTICE.md) for retained source attribution.

## Google

- Anonymous full-JSON requests fall back once from the legacy web client to the
  browser-extension client after HTTP 429, preserving translation metadata,
  dictionary definitions and spell-check data. The fallback is regression-tested.
- Rewritten spell-check orchestration preserves leading/trailing whitespace,
  tabs, blank lines, CRLF and Unicode when there is no correction.
- The bounded LRU cache includes both language and sentence, stores only
  successful responses and holds at most 200 entries.
- A plugin-wide semaphore limits active spell-check requests to four; sentences
  are processed in batches of four rather than creating a coroutine per sentence.
- Correction offsets use actual word ranges, including repeated words and UTF-16
  surrogate pairs. Insertions/deletions are not assigned misleading word offsets.
- Free translation, dictionary and speech endpoints are retained. Official
  Google translation and Vision OCR remain optional user-key services.

## Bing

- Rewritten token manager serializes refreshes with a coroutine mutex. Translation,
  spell checking and speech reuse the same cached token.
- Token expiry uses the page's lifetime when supplied, capped at one hour.
- Page parsing accepts whitespace and either attribute quote style, decodes JSON
  string escapes and rejects incomplete token data. JavaScript is not evaluated.
- Missing fields and provider challenge pages return a structured response error.

## AI

- Text and vision share one validated connection and response pipeline. Settings
  are copied at the start of each request; subsequent requests pick up changes.
- Remote endpoints require the user's key. Explicit loopback endpoints can run
  without a key for compatible local services.
- URLs, model, finite temperature, token bounds, custom JSON headers and API-key
  characters are validated before sending. Settings use the same validation when
  saved. Reserved/duplicate headers and header injection are rejected.
- JPEG data URIs use `image/jpeg`. Empty/unsupported images fail before network access.
- No automatic retry is enabled for AI requests. Truncation/refusal/invalid
  responses remain errors rather than silently displaying partial results.
- New installations require the user to choose an available model instead of
  defaulting to an obsolete provider model. Existing model and key settings remain.

## Shared HTTP and privacy

- Partial plugin settings changes preserve unspecified keys/models/other fields
  rather than silently resetting them to defaults. Rejected changes leave both
  live settings and persisted values intact.
- Both speech plugins bound long words and emoji to their request limits without
  splitting surrogate pairs; audio assembly avoids quadratic byte copying.
  Bing speech formats SSML rates independently of the Windows locale and fetches
  chunks in order. Bing spell checking preserves separators and uses diff positions
  for repeated words.
- AI spell-check highlights accept only spans matching the original input;
  invalid model offsets are omitted instead of being clamped onto unrelated text.
- Text and binary requests use the same HTTP error mapping, including credit
  requirements, authentication, rate limits and invalid input.
- Credit errors are provider-neutral. Raw provider error bodies and malformed JSON
  excerpts are no longer placed in plugin log messages.
- Cancellation propagation and the GET-only retry policy are retained.

## Installation and scope

Exit the application, including its tray icon, before replacing JARs. Use the
updated `:app:refreshWindowsApp` task to refresh the existing installation without
removing settings/history. A plugin fingerprint change is expected: if the Plugins
page asks for verification, use **Accept Update** to retain that plugin's settings.
Do not uninstall/reinstall to bypass verification.

Free web endpoints can be changed or limited by their providers. The developer
does not pay for users' AI requests; users choose their account/model and are
responsible for any charges their provider applies. No paid AI call is required
by the automated tests; local HTTP fixtures exercise the protocol.
