package org.shariftranslate.plugins.google

import org.shariftranslate.api.language.LanguageCode
import org.shariftranslate.api.plugin.PluginContext
import org.shariftranslate.api.plugin.ServiceError
import org.shariftranslate.api.plugin.SupportedLanguages
import org.shariftranslate.api.translator.TranslationRequest
import org.shariftranslate.api.translator.TranslationResponse
import org.shariftranslate.api.translator.Translator
import org.shariftranslate.plugins.common.ApiConfig
import org.shariftranslate.plugins.common.KtorHttpClient
import org.shariftranslate.plugins.common.createJsonParser
import org.shariftranslate.plugins.google.common.GoogleLanguageMapper
import org.shariftranslate.plugins.google.common.OfficialTranslateResponse
import org.shariftranslate.plugins.google.common.TranslateResponse
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.toResultOr

class GoogleTranslatorService(
    private val pluginContext: PluginContext,
    private val settings: GoogleSettings,
    private val httpClient: KtorHttpClient,
    private val languageMapper: GoogleLanguageMapper,
    private val apiConfig: ApiConfig
) : Translator {

    override val id: String = "google-translator"
    override val name: String = "Google Translate"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/google-translate-icon.svg"

    private val officialParser = createJsonParser<OfficialTranslateResponse>(pluginContext)
    private val translateParser = createJsonParser<TranslateResponse>(pluginContext)

    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    companion object {
        private const val TRANSLATE_PRIMARY = "https://translate.googleapis.com/translate_a/single"
        private const val TRANSLATE_FALLBACK = "https://clients5.google.com/translate_a/t"
        private const val TRANSLATE_OFFICIAL = "https://translation.googleapis.com/language/translate/v2"
        private val TRANSLATE_FEATURES = listOf("t", "bd", "at", "ex", "ld", "md", "rw", "rm", "ss", "qc")
    }

    override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
        languageMapper.getSupportedLanguages()

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
        if (settings.translateApiKey.isNotBlank()) {
            pluginContext.logger.info("Using official Google Translate API")
            val officialResult = translateWithOfficialAPI(request)
            if (officialResult.isOk) return officialResult
            pluginContext.logger.info("Official API failed, falling back to unofficial endpoint")
        }
        return translateWithUnofficialAPI(request)
    }

    private suspend fun translateWithOfficialAPI(
        request: TranslationRequest
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val sourceTag = languageMapper.toProviderCode(request.sourceLanguage)
        val targetTag = languageMapper.toProviderCode(request.targetLanguage)

        val requestBody = mapOf(
            "q" to request.text,
            "target" to targetTag,
            "format" to "text"
        ) + if (request.sourceLanguage != LanguageCode.AUTO) mapOf("source" to sourceTag) else emptyMap()

        val responseString = httpClient.sendJson(
            url = TRANSLATE_OFFICIAL,
            headers = apiConfig.createJsonHeaders(),
            body = requestBody,
            queryParams = mapOf("key" to settings.translateApiKey)
        ).bind()

        val parsed = officialParser.parse(responseString).bind()
        val firstTranslation = parsed.data.translations.firstOrNull()
            .toResultOr { ServiceError.InvalidResponseError("No translation in response", null) }
            .bind()

        TranslationResponse(
            translatedText = firstTranslation.translatedText,
            detectedLanguage = firstTranslation.detectedSourceLanguage?.let {
                languageMapper.fromProviderCode(it)
            }
        )
    }

    private suspend fun translateWithUnofficialAPI(
        request: TranslationRequest
    ): Result<TranslationResponse, ServiceError> {
        val sourceTag = languageMapper.toProviderCode(request.sourceLanguage)
        val targetTag = languageMapper.toProviderCode(request.targetLanguage)

        val primaryResult = tryPrimaryEndpoint(request.text, sourceTag, targetTag)
        if (primaryResult.isOk) return primaryResult

        pluginContext.logger.info("Primary endpoint failed, trying fallback")
        return tryFallbackEndpoint(request.text, sourceTag, targetTag)
    }

    private suspend fun tryPrimaryEndpoint(
        text: String,
        sourceTag: String,
        targetTag: String
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val responseString = httpClient.get(
            url = TRANSLATE_PRIMARY,
            headers = apiConfig.createHeaders(),
            queryParams = mapOf(
                "client" to "gtx",
                "ie" to "UTF-8",
                "oe" to "UTF-8",
                "dj" to 1,
                "dt" to TRANSLATE_FEATURES,
                "sl" to sourceTag,
                "tl" to targetTag,
                "q" to text
            )
        ).bind()

        val parsed = translateParser.parse(responseString).bind()
        val translatedText = parsed.sentences.joinToString("") { it.text.orEmpty() }
        if (translatedText.isBlank()) {
            Err(ServiceError.InvalidResponseError("No translation in Google response", null)).bind()
        }
        val detectedLang = languageMapper.fromProviderCode(parsed.sourceLanguage)
        val alternatives = parsed.dictionary?.firstOrNull()?.terms?.take(3) ?: emptyList()

        TranslationResponse(
            translatedText = translatedText.trim(),
            detectedLanguage = detectedLang,
            alternatives = alternatives
        )
    }

    private suspend fun tryFallbackEndpoint(
        text: String,
        sourceTag: String,
        targetTag: String
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val parsed: JsonArray = httpClient.fetchJson<JsonArray>(
            url = TRANSLATE_FALLBACK,
            headers = apiConfig.createHeaders(),
            queryParams = mapOf(
                "client" to "dict-chrome-ex",
                "dj" to 1,
                "sl" to sourceTag,
                "tl" to targetTag,
                "q" to text
            )
        ).bind()

        parseGoogleFallbackResponse(parsed).bind()
    }

}

/** Google's fallback has different response shapes for explicit source and auto-detection. */
internal fun parseGoogleFallbackResponse(parsed: JsonArray): Result<TranslationResponse, ServiceError> {
    val first = parsed.firstOrNull()
    val translation = if (first is JsonArray) first.getOrNull(0) else first
    val translatedText = (translation as? JsonPrimitive)?.takeIf { it.isString }
        ?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: return Err(ServiceError.InvalidResponseError("No translation in fallback response", null))
    val detected = ((first as? JsonArray)?.getOrNull(1) as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() }?.let { GoogleLanguageMapper.fromProviderCode(it) }
    return Ok(TranslationResponse(translatedText.trim(), detected))
}
