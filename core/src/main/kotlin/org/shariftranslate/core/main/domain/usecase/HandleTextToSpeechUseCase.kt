package org.shariftranslate.core.main.domain.usecase

import org.shariftranslate.api.core.Logger
import org.shariftranslate.api.language.LanguageCode
import org.shariftranslate.api.plugin.NotificationType
import org.shariftranslate.api.plugin.SupportedLanguages
import org.shariftranslate.api.tts.TTSAudio
import org.shariftranslate.api.tts.TTSRequest
import org.shariftranslate.api.tts.TextToSpeech
import org.shariftranslate.core.audio.AudioPlayer
import org.shariftranslate.core.main.mvi.MainState
import org.shariftranslate.core.settings.data.ActiveServiceManager
import org.shariftranslate.core.settings.data.Configuration
import org.shariftranslate.core.settings.data.ExtraOutputSource
import org.shariftranslate.core.settings.data.TextSource
import org.shariftranslate.core.shared.AppConstants
import org.shariftranslate.core.shared.StatusCode
import org.shariftranslate.core.shared.arch.ServiceType
import org.shariftranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class HandleTextToSpeechUseCase(
    private val activeServiceManager: ActiveServiceManager,
    private val settingsState: StateFlow<Configuration>,
    private val audioPlayer: AudioPlayer,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("HandleTextToSpeechUseCase")

    /** Mirrors [AudioPlayer.isPlaying] — collected by [org.shariftranslate.core.main.mvi.MainStore]
     *  to drive the listen/stop button toggle in the UI. */
    val isPlaying: StateFlow<Boolean> = audioPlayer.isPlaying

    /**
     * Stops any audio currently playing. No-op if nothing is playing.
     * Called when the user clicks the Stop button or dispatches [org.shariftranslate.core.main.mvi.MainIntent.StopTTS].
     */
    fun stop() {
        logger.info("TTS stopped by user")
        audioPlayer.stop()
    }

    suspend operator fun invoke(
        currentState: MainState,
        textSource: TextSource,
        textOverride: String?,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit
    ) {
        val textToSynthesize = textOverride ?: getTextFromSource(currentState, textSource)
        val language = determineLanguage(currentState, textSource)

        if (textToSynthesize.isBlank()) {
            logger.debug("TTS skipped: text is blank")
            onStatusUpdate(StatusCode.NoTextToSpeak, NotificationType.WARNING, true)
            return
        }

        if (language == null) {
            logger.warn("Cannot determine language for TTS")
            onStatusUpdate(StatusCode.CannotDetermineLanguage, NotificationType.WARNING, true)
            return
        }

        val ttsService = activeServiceManager.getActiveService<TextToSpeech>(ServiceType.TTS)
        if (ttsService == null) {
            logger.warn("No TTS service available")
            onStatusUpdate(StatusCode.NoTtsServiceActive, NotificationType.WARNING, true)
            return
        }

        val languageSupported = when (val supported = ttsService.supportedLanguages) {
            is SupportedLanguages.All -> true
            is SupportedLanguages.Dynamic -> true
            is SupportedLanguages.Specific -> language in supported.languages
        }

        if (!languageSupported) {
            logger.warn("TTS service '${ttsService.name}' does not support language: $language")
            onStatusUpdate(StatusCode.TtsLanguageNotSupported(ttsService.name), NotificationType.WARNING, true)
            return
        }

        logger.info("Starting TTS with '${ttsService.name}' for $textSource, language: $language")
        onStatusUpdate(StatusCode.ConvertingToSpeech, NotificationType.INFO, false)

        val request = TTSRequest.ByLanguage(text = textToSynthesize, language = language)

        val result = withTimeoutOrNull(AppConstants.TTS_TIMEOUT_MS) {
            ttsService.synthesize(request)
        }

        if (result == null) {
            logger.error("TTS timed out after ${AppConstants.TTS_TIMEOUT_MS}ms")
            onStatusUpdate(StatusCode.TtsTimeout, NotificationType.ERROR, true)
            return
        }

        result
            .onOk { response ->
                when (val audio = response.audio) {
                    is TTSAudio.Bytes -> {
                        logger.info("TTS successful — playing ${audio.data.size} bytes (${audio.format})")
                        onStatusUpdate(StatusCode.PlayingAudio, NotificationType.INFO, false)
                        audioPlayer.play(audio)
                        onStatusUpdate(StatusCode.AudioPlaybackComplete, NotificationType.SUCCESS, true)
                    }

                    is TTSAudio.StreamUrl -> {
                        logger.info("TTS returned stream URL — downloading audio")
                        onStatusUpdate(StatusCode.DownloadingAudio, NotificationType.INFO, false)

                        val bytes: ByteArray? = withContext(Dispatchers.IO) {
                            runCatching {
                                java.net.URI(audio.url).toURL().openConnection().apply {
                                    connectTimeout = 15000
                                    readTimeout = 15000
                                }.getInputStream().use { it.readBytes() }
                            }.getOrNull()
                        }

                        if (bytes == null || bytes.isEmpty()) {
                            logger.error("Failed to download audio stream from ${audio.url}")
                            onStatusUpdate(StatusCode.AudioDownloadFailed, NotificationType.ERROR, true)
                        } else {
                            logger.info("Stream downloaded — playing ${bytes.size} bytes (${audio.format})")
                            onStatusUpdate(StatusCode.PlayingAudio, NotificationType.INFO, false)
                            audioPlayer.play(TTSAudio.Bytes(bytes, audio.format))
                            onStatusUpdate(StatusCode.AudioPlaybackComplete, NotificationType.SUCCESS, true)
                        }
                    }
                }
            }
            .onErr { error ->
                logger.error("TTS failed: ${error.message}", error.cause)
                val summary = error.message?.lines()?.firstOrNull()?.take(120) ?: "Unknown error"
                onStatusUpdate(StatusCode.TtsFailed(summary), NotificationType.ERROR, true)
            }
    }

    private fun getTextFromSource(state: MainState, source: TextSource): String =
        when (source) {
            TextSource.Input -> state.inputText
            TextSource.Output -> state.translatedText
            TextSource.ExtraOutput -> state.extraOutputText
        }

    private fun determineLanguage(state: MainState, source: TextSource): LanguageCode? =
        when (source) {
            TextSource.Input -> if (state.sourceLanguage == LanguageCode.AUTO) state.detectedSourceLanguage else state.sourceLanguage
            TextSource.Output -> state.targetLanguage
            TextSource.ExtraOutput -> when (settingsState.value.extraOutputSource) {
                ExtraOutputSource.Input -> if (state.sourceLanguage == LanguageCode.AUTO) state.detectedSourceLanguage else state.sourceLanguage
                ExtraOutputSource.Output -> state.targetLanguage
            }
        }

    fun shutdown() {
        logger.info("Shutting down TTS use case")
        runCatching { audioPlayer.close() }.onFailure { e ->
            logger.error("Error closing audio player during shutdown", e)
        }
    }
}
