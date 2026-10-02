package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class LookupWordUseCase(
    private val scope: CoroutineScope,
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("LookupWordUseCase")
    private var lookupJob: Job? = null
    private val requestLock = Any()
    private var generation = 0L

    fun cancel() = synchronized(requestLock) {
        generation++
        lookupJob?.cancel()
        lookupJob = null
    }

    suspend operator fun invoke(
        word: String,
        language: LanguageCode,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (StatusCode, NotificationType, Boolean) -> Unit
    ) {
        val request = synchronized(requestLock) {
            lookupJob?.cancel(CancellationException("New lookup requested"))
            ++generation
        }
        val publish: (MainState.() -> MainState) -> Unit = { transform ->
            synchronized(requestLock) { if (request == generation) updateState(transform) }
        }
        val report: suspend (StatusCode, NotificationType, Boolean) -> Unit = { code, type, temporary ->
            currentCoroutineContext().ensureActive()
            if (synchronized(requestLock) { request == generation }) onStatusUpdate(code, type, temporary)
        }

        if (word.isBlank()) {
            onStatusUpdate(StatusCode.NoWordToLookup, NotificationType.WARNING, true)
            publish { copy(isDictionaryLoading = false, dictionaryWord = "", dictionaryEntries = emptyList()) }
            return
        }

        val dictionary = activeServiceManager.getActiveService<Dictionary>(ServiceType.DICTIONARY)
        if (dictionary == null) {
            logger.warn("No dictionary service available")
            onStatusUpdate(StatusCode.NoDictionaryServiceActive, NotificationType.ERROR, true)
            publish { copy(isDictionaryLoading = false, dictionaryFailed = true, dictionaryWord = word, dictionaryEntries = emptyList()) }
            return
        }

        logger.info("Looking up '$word' with '${dictionary.name}'")

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val updateState = publish
            val onStatusUpdate = report
            try {
                onStatusUpdate(StatusCode.LookingUpWord, NotificationType.INFO, false)
                updateState {
                    copy(
                        isDictionaryLoading = true,
                        dictionaryEntries = emptyList(),
                        dictionaryWord = word,
                        dictionaryFailed = false
                    )
                }

                val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
                    dictionary.lookup(DictionaryRequest(word, language))
                }
                ensureActive()

                if (result == null) {
                    logger.warn("Dictionary lookup timed out for '$word'")
                    onStatusUpdate(StatusCode.DictionaryTimeout, NotificationType.WARNING, true)
                    updateState { copy(isDictionaryLoading = false, dictionaryFailed = true) }
                    return@launch
                }

                result.fold(
                    success = { response ->
                        if (response.entries.isEmpty()) {
                            logger.info("No definitions found for '$word'")
                            onStatusUpdate(StatusCode.DictionaryNotFound(word), NotificationType.INFO, true)
                            updateState { copy(isDictionaryLoading = false, dictionaryEntries = emptyList()) }
                        } else {
                            logger.info("Found ${response.entries.size} entries for '$word'")
                            onStatusUpdate(StatusCode.DictionaryReady, NotificationType.INFO, true)
                            updateState {
                                copy(
                                    isDictionaryLoading = false,
                                    dictionaryEntries = response.entries,
                                    dictionaryFailed = false
                                )
                            }
                        }
                    },
                    failure = { error ->
                        val msg = error.toString()
                        logger.warn("Dictionary lookup failed for '$word': $msg")
                        onStatusUpdate(StatusCode.DictionaryFailed(msg), NotificationType.ERROR, true)
                        updateState { copy(isDictionaryLoading = false, dictionaryFailed = true) }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                logger.warn("Unexpected error during dictionary lookup: $msg")
                onStatusUpdate(StatusCode.DictionaryFailed(msg), NotificationType.ERROR, true)
                updateState { copy(isDictionaryLoading = false, dictionaryFailed = true) }
            }
        }
        synchronized(requestLock) {
            if (request == generation) lookupJob = job else job.cancel()
        }
        job.start()
    }
}
