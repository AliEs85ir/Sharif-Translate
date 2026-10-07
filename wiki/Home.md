# SharifTranslate Wiki

Welcome to the SharifTranslate documentation wiki.

---

## For Users

- [Installing Plugins](Installing-Plugins.md) — how to find, install, and configure plugins
- [Adding a Language](Adding-a-Language.md) — translate the SharifTranslate interface into your language
- [Adding a Theme](Adding-a-Theme.md) — install community themes or create your own

## For Developers

- [Building from Source](Building-from-Source.md) — compile and run SharifTranslate locally
- [Architecture](Architecture.md) — how SharifTranslate is structured and why
- [Creating a Plugin](Creating-a-Plugin.md) — build your own translation engine, OCR, or TTS plugin
- [Contributing](Contributing.md) — how to contribute code, docs, or translations

### Community plugins

The Plugins page links to the [shariftranslate-plugin GitHub topic](https://github.com/topics/shariftranslate-plugin). Users download plugin JARs from a source they trust and install them through Settings → Plugins. Automatic marketplace installation is not implemented.

→ [Plugin development guide](Creating-a-Plugin.md)

## Plugin examples (in the repo)

The bundled plugins are the best reference for plugin development:

| | Source |
|--|--------|
| 🔵 | [`plugins/google-services/`](../plugins/google-services/src/main/kotlin) — Translator, TTS, OCR, Spell Checker, Dictionary with API key settings |
| 🟠 | [`plugins/bing-services/`](../plugins/bing-services/src/main/kotlin) — Translator, TTS, Spell Checker with token auth |
| 🤖 | [`plugins/ai-services/`](../plugins/ai-services/src/main/kotlin) — Translator, Summarizer, Rewriter, Spell Checker, Dictionary, Vision OCR via OpenRouter |
| 🔧 | [`plugins/common/`](../plugins/common/src/main/kotlin) — shared HTTP client, language mapper, JSON parser |

---

Can't find what you're looking for? [Open an issue](../README.md) and ask.