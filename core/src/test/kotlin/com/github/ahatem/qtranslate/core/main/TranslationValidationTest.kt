package com.github.ahatem.qtranslate.core.main

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.*
import com.github.ahatem.qtranslate.api.translator.*
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.main.domain.usecase.*
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.*
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class TranslationValidationTest {
    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
    private val logFactory = object : LoggerFactory { override fun getLogger(name: String) = logger }

    private class FakeTranslator(val action: suspend (TranslationRequest) -> Result<TranslationResponse, ServiceError>) : Translator {
        override val id = "google-translator"
        override val name = "Validation translator"
        override val version = "1"
        override val iconPath: String? = null
        override val supportedLanguages = SupportedLanguages.All
        override suspend fun translate(request: TranslationRequest) = action(request)
    }

    private inner class Fixture(translator: Translator?, config: Configuration = Configuration.DEFAULT) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = MutableStateFlow(config)
        val services = MutableStateFlow<Map<String, Service>>(translator?.let { mapOf(it.id to it) } ?: emptyMap())
        val manager = ActiveServiceManager(services, settings)
        val history = HistoryRepository(Files.createTempDirectory("translation-validation").toFile(), logger, Json)
        val state = MutableStateFlow(MainState(sourceLanguage = LanguageCode.AUTO, targetLanguage = LanguageCode.FARSI))
        val useCase = TranslateTextUseCase(scope, settings, manager, history, SummarizeUseCase(manager, logFactory), RewriteUseCase(manager, logFactory), logFactory)
        suspend fun translate(text: String): String? {
            state.update { it.copy(inputText = text) }
            return useCase({ state.value }, { state.update(it) }, { _, _, _ -> })
        }
    }

    @Test fun lateCancelledResponseCannotOverwriteNewerTranslation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val fixture = Fixture(FakeTranslator { request ->
            if (request.text == "A") {
                entered.complete(Unit)
                withContext(NonCancellable) { delay(180) }
            }
            Ok(TranslationResponse("translated ${request.text}"))
        })
        try {
            val first = async { fixture.translate("A") }
            entered.await()
            assertEquals("translated B", fixture.translate("B"))
            assertNull(first.await())
            assertEquals("translated B", fixture.state.value.translatedText)
            assertEquals("B", fixture.state.value.inputText)
            assertFalse(fixture.state.value.isLoading)
        } finally { fixture.scope.cancel() }
    }

    @Test fun cancelStopsPublicationEvenIfPluginIgnoresCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val fixture = Fixture(FakeTranslator {
            entered.complete(Unit)
            withContext(NonCancellable) { delay(100) }
            Ok(TranslationResponse("obsolete"))
        })
        try {
            val first = async { fixture.translate("input") }
            entered.await()
            fixture.useCase.cancel()
            fixture.state.update { it.copy(isLoading = false) }
            assertNull(first.await())
            assertEquals("", fixture.state.value.translatedText)
        } finally { fixture.scope.cancel() }
    }

    @Test fun detectedLanguageRulesPersistTheActualTargetAndKeepInput() = runBlocking {
        val requests = mutableListOf<TranslationRequest>()
        val fixture = Fixture(FakeTranslator {
            requests.add(it)
            Ok(TranslationResponse("translated ${it.targetLanguage.tag}", LanguageCode.ENGLISH))
        }, Configuration.DEFAULT.copy(translationRules = listOf(TranslationRule("en", "de"))))
        val original = "  Hello!\nSecond line — 😀\t  "
        try {
            fixture.translate(original)
            assertEquals(listOf("fa", "de"), requests.map { it.targetLanguage.tag })
            assertTrue(requests.all { it.text == original })
            assertEquals(original, fixture.state.value.inputText)
            assertEquals("de", fixture.state.value.history.last().targetLanguage)
            assertEquals("de", fixture.history.loadHistory().last().targetLanguage)
        } finally { fixture.scope.cancel() }
    }

    @Test fun missingTranslatorAndBlankInputClearLoadingAndOldResult() = runBlocking {
        val fixture = Fixture(null)
        try {
            fixture.state.update { it.copy(isLoading = true, translatedText = "previous") }
            assertNull(fixture.translate("hello"))
            assertFalse(fixture.state.value.isLoading)
            assertEquals("", fixture.state.value.translatedText)
            assertNull(fixture.translate("\n \t"))
        } finally { fixture.scope.cancel() }
    }

    @Test fun thrownPluginExceptionBecomesARecoverableState() = runBlocking {
        var fail = true
        val fixture = Fixture(FakeTranslator {
            if (fail) error("malformed plugin response") else Ok(TranslationResponse("سلام"))
        })
        try {
            assertNull(fixture.translate("Hello"))
            assertFalse(fixture.state.value.isLoading)
            fail = false
            assertEquals("سلام", fixture.translate("Hello"))
        } finally { fixture.scope.cancel() }
    }

    @Test fun disabledAndWrongTypeServicesAreNotResolvedAsTranslator() {
        val fixture = Fixture(FakeTranslator { Ok(TranslationResponse("test")) })
        try {
            fixture.settings.value = fixture.settings.value.copy(disabledServices = setOf("google-translator"))
            assertNull(fixture.manager.getActiveService<Translator>(ServiceType.TRANSLATOR))
            assertNull(fixture.manager.getActiveService<Translator>(ServiceType.DICTIONARY))
        } finally { fixture.scope.cancel() }
    }
}
