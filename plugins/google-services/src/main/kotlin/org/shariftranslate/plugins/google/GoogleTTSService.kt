package org.shariftranslate.plugins.google

import org.shariftranslate.api.language.LanguageCode
import org.shariftranslate.api.plugin.PluginContext
import org.shariftranslate.api.plugin.ServiceError
import org.shariftranslate.api.plugin.SupportedLanguages
import org.shariftranslate.api.tts.AudioFormat
import org.shariftranslate.api.tts.TTSAudio
import org.shariftranslate.api.tts.TTSRequest
import org.shariftranslate.api.tts.TTSResponse
import org.shariftranslate.api.tts.TextToSpeech
import java.io.ByteArrayOutputStream
import org.shariftranslate.plugins.common.textChunks
import org.shariftranslate.plugins.common.ApiConfig
import org.shariftranslate.plugins.common.KtorHttpClient
import org.shariftranslate.plugins.google.common.GoogleLanguageMapper
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.getOrElse

class GoogleTTSService(
    private val pluginContext: PluginContext,
    private val httpClient: KtorHttpClient,
    private val languageMapper: GoogleLanguageMapper,
    private val apiConfig: ApiConfig,
    private val endpoint: String = "https://translate.googleapis.com/translate_tts"
) : TextToSpeech {

    override val id: String = "google-tts"
    override val name: String = "Google TTS"
    override val version: String = "1.1.0"
    override val iconPath: String = "assets/google-translate-icon.svg"

    // Google TTS supports a dynamic language set — same source as the translator.
    // The core calls fetchSupportedLanguages() once and caches the result.
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
        languageMapper.getSupportedLanguages()

    companion object {
        private const val TTS_ENDPOINT = "https://translate.googleapis.com/translate_tts"
        private const val MAX_CHUNK_LENGTH = 200
    }

    override suspend fun synthesize(request: TTSRequest): Result<TTSResponse, ServiceError> =
        coroutineBinding {
            // TTSRequest is a sealed interface — resolve the language from whichever variant
            // the core dispatched. ByVoice carries the language on the Voice itself;
            // ByLanguage carries it directly. Google TTS is not a VoiceSupport service,
            // so the core will never send ByVoice here, but we handle it defensively.
            val language = when (request) {
                is TTSRequest.ByLanguage -> request.language
                is TTSRequest.ByVoice   -> request.voice.language
            }

            val langTag = languageMapper.toProviderCode(language)
            val speed = request.speed
            val chunks = partitionText(request.text)

            val audioData = tryPrimaryEndpoint(chunks, langTag, speed).getOrElse {
                pluginContext.logger.info("Primary TTS endpoint failed, trying fallback")
                tryFallbackEndpoint(chunks, langTag, speed).bind()
            }

            TTSResponse(audio = TTSAudio.Bytes(audioData, AudioFormat.MP3))
        }

    private suspend fun tryPrimaryEndpoint(
        chunks: List<String>,
        langTag: String,
        speed: Float
    ): Result<ByteArray, ServiceError> = coroutineBinding {
        val audioChunks = mutableListOf<ByteArray>()
        for ((idx, chunk) in chunks.withIndex()) {
            val bytes = httpClient.getBytes(
                url = endpoint,
                headers = apiConfig.createHeaders(),
                queryParams = mapOf(
                    "client" to "gtx",
                    "ie" to "UTF-8",
                    "tl" to langTag,
                    "q" to chunk,
                    "total" to chunks.size,
                    "idx" to idx,
                    "textlen" to chunk.length,
                )
            ).bind()
            audioChunks.add(bytes)
        }
        ByteArrayOutputStream().also { out -> audioChunks.forEach { out.write(it) } }.toByteArray()
    }

    private suspend fun tryFallbackEndpoint(
        chunks: List<String>,
        langTag: String,
        speed: Float
    ): Result<ByteArray, ServiceError> = coroutineBinding {
        val audioChunks = mutableListOf<ByteArray>()
        for (chunk in chunks) {
            val bytes = httpClient.getBytes(
                url = endpoint,
                headers = apiConfig.createHeaders(),
                queryParams = mapOf(
                    "client" to "tw-ob",
                    "tl" to langTag,
                    "q" to chunk
                )
            ).bind()
            audioChunks.add(bytes)
        }
        ByteArrayOutputStream().also { out -> audioChunks.forEach { out.write(it) } }.toByteArray()
    }

    private fun partitionText(text: String): List<String> =
        textChunks(text, MAX_CHUNK_LENGTH).filter { it.isNotBlank() }
}
