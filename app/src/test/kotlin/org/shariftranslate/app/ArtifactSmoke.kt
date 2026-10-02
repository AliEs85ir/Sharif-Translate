package org.shariftranslate.app

import org.shariftranslate.api.language.LanguageCode
import org.shariftranslate.api.plugin.ServiceError
import org.shariftranslate.api.translator.Translator
import org.shariftranslate.api.translator.TranslationRequest
import org.shariftranslate.api.dictionary.Dictionary
import org.shariftranslate.api.dictionary.DictionaryRequest
import org.shariftranslate.api.summarizer.Summarizer
import org.shariftranslate.api.summarizer.SummarizeRequest
import org.shariftranslate.api.rewriter.Rewriter
import org.shariftranslate.api.rewriter.RewriteRequest
import org.shariftranslate.api.spellchecker.SpellChecker
import org.shariftranslate.api.spellchecker.SpellCheckRequest
import org.shariftranslate.api.ocr.*
import org.shariftranslate.core.collections.CollectionRepository
import org.shariftranslate.core.main.mvi.*
import org.shariftranslate.core.settings.data.*
import org.shariftranslate.core.settings.mvi.SettingsIntent
import org.shariftranslate.core.shared.arch.ServiceType
import com.github.michaelbull.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.File
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Run with the shipped runtime and SharifTranslate.jar on the classpath, never the source output. */
fun main(args: Array<String>) = runBlocking {
    val data = File(args[0]).absoluteFile.also { it.mkdirs() }
    val endpoint = args.getOrNull(1)
    System.setProperty("appData", data.path)
    AppDataDirectory.installBundledResources(data)
    val logs = ConsoleLoggerFactory(ConsoleLoggerFactory.LogLevel.WARN)
    val repository = SettingsRepository(data, Json { ignoreUnknownKeys = true }, logs.getLogger("ValidationSettings"))
    val config = repository.loadInitialConfiguration().copy(
        autoCheckForUpdates = false, isSpellCheckingEnabled = false,
        preferredTargetLanguage = "fa", preferredSourceLanguage = "en", closeButtonBehavior = CloseButtonBehavior.EXIT,
        themeId = "builtin:flat_light", isGlobalHotkeysEnabled = false
    )
    check(repository.updateConfiguration(config).isOk)
    val deps = buildDependencies(data, logs, repository, config)
    try {
        deps.pluginManager.loadAndProcessPlugins()
        check(deps.pluginManager.plugins.value.size == 3)
        println("PASS: 3 bundled plugins loaded from distribution; core location=${MainStore::class.java.protectionDomain.codeSource.location}")
        val statuses = mutableListOf<MainEvent>()
        val collector = launch { deps.mainStore.events.collect { statuses.add(it) } }
        suspend fun translate(text: String, source: LanguageCode, target: LanguageCode, engine: String): String {
            deps.settingsStore.dispatch(SettingsIntent.UpdateServiceInActivePreset(ServiceType.TRANSLATOR, engine))
            withTimeout(5000) { deps.mainStore.state.first { it.availableServices.any { service -> service.id == engine } } }
            delay(80) // allow the configuration StateFlow to reach ActiveServiceManager
            deps.mainStore.dispatch(MainIntent.SelectSourceLanguage(source))
            deps.mainStore.dispatch(MainIntent.SelectTargetLanguage(target))
            deps.mainStore.dispatch(MainIntent.UpdateInputText(text))
            val beforeHistory = deps.mainStore.state.value.history
            deps.mainStore.dispatch(MainIntent.Translate())
            val state = withTimeout(45000) { deps.mainStore.state.first {
                !it.isLoading && it.translatedText.isNotBlank() && it.history != beforeHistory && it.history.lastOrNull()?.translatorId == engine
            } }
            check(state.inputText == text) { "Input mutated" }
            println("PASS: $engine ${source.tag}->${target.tag} input=${text.length} output=${state.translatedText.length}")
            return state.translatedText
        }
        if (endpoint != "prepare") {
            translate("Hello world.", LanguageCode.ENGLISH, LanguageCode.FARSI, "google-translator")
            translate("A sentence with punctuation: hello, world!", LanguageCode.AUTO, LanguageCode.FARSI, "google-translator")
            translate("Hello!\nSecond line — Unicode 😀", LanguageCode.ENGLISH, LanguageCode.FARSI, "google-translator")
            translate("سلام دنیا!\nاین یک تست است.", LanguageCode.FARSI, LanguageCode.ENGLISH, "google-translator")
            translate("Hello world.", LanguageCode.AUTO, LanguageCode.FARSI, "bing-translator")
            val history = deps.mainStore.state.value.history
            check(history.size >= 5)
            deps.mainStore.dispatch(MainIntent.UndoTranslation)
            check(deps.mainStore.state.value.canRedo)
            deps.mainStore.dispatch(MainIntent.RedoTranslation)
            check(!deps.mainStore.state.value.isLoading)
            println("PASS: translation history undo/redo")
        }
        if (endpoint != null && endpoint != "prepare") {
            check(deps.pluginManager.applySettingsFromMap("ai-plugin", mapOf("baseUrl" to endpoint, "model" to "validation-model", "apiKey" to "", "customHeaders" to "")).isOk)
            translate("Hello!\nSecond line 😀", LanguageCode.AUTO, LanguageCode.FARSI, "ai-translator")
            val services = deps.pluginManager.activeServices.value
            check((services["ai-summarizer"] as Summarizer).summarize(SummarizeRequest("A long text to summarize.")).isOk)
            check((services["ai-rewriter"] as Rewriter).rewrite(RewriteRequest("A sentence to rewrite.")).isOk)
            check((services["ai-dictionary"] as Dictionary).lookup(DictionaryRequest("hello", LanguageCode.ENGLISH)).get()?.entries?.isNotEmpty() == true)
            check((services["ai-spell-checker"] as SpellChecker).check(SpellCheckRequest("hello", LanguageCode.ENGLISH)).isOk)
            val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
            check((services["ai-ocr"] as OCR).extractText(OCRRequest(ImageData(bytes, "png", 8, 8))).isOk)
            println("PASS: AI translation, summary, rewrite, dictionary, spell check, vision over controlled local HTTP")
            val ai = services["ai-translator"] as Translator
            for (text in listOf("ERROR_429", "MALFORMED", "TRUNCATED")) {
                check(ai.translate(TranslationRequest(text, LanguageCode.ENGLISH, LanguageCode.FARSI)).isErr)
            }
            check(deps.pluginManager.applySettingsFromMap("ai-plugin", mapOf("baseUrl" to "http://127.0.0.1:1/v1")).isOk)
            check(ai.translate(TranslationRequest("offline", LanguageCode.ENGLISH, LanguageCode.FARSI)).getError() is ServiceError.NetworkError)
            println("PASS: AI rate limit, malformed/truncated response, refused connection")
        }
        val collections = deps.collectionRepository
        val collection = collections.create("Validation فارسی")
        val text = "سلام!\nHello 😀"
        collections.add(collection, text)
        collections.add(CollectionRepository.FAVORITES_ID, text)
        collections.load()
        check(collections.containing(text).size == 2)
        println("PASS: Favorites and Collections persisted and reloaded")
        check(deps.settingsStore.saveChanges().isOk)
        check(repository.loadInitialConfiguration().preferredTargetLanguage == "fa")
        check(MainStore::class.java.protectionDomain.codeSource.location.path.endsWith("SharifTranslate.jar"))
        collector.cancelAndJoin()
        println("ARTIFACT VALIDATION PASSED")
    } finally {
        deps.mainStore.onShutdown()
        deps.pluginManager.shutdown()
        deps.appScope.cancel()
    }
}
