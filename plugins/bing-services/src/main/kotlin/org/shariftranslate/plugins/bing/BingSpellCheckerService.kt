package org.shariftranslate.plugins.bing

import org.shariftranslate.api.plugin.PluginContext
import org.shariftranslate.api.plugin.ServiceError
import org.shariftranslate.api.plugin.SupportedLanguages
import org.shariftranslate.api.spellchecker.*
import org.shariftranslate.plugins.common.textChunks
import org.shariftranslate.plugins.common.ApiConfig
import org.shariftranslate.plugins.common.KtorHttpClient
import org.shariftranslate.plugins.common.createJsonParser
import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import kotlinx.coroutines.coroutineScope

class BingSpellCheckerService(
    private val pluginContext: PluginContext,
    private val httpClient: KtorHttpClient,
    private val authManager: BingAuthManager,
    private val languageMapper: BingLanguageMapper,
    private val apiConfig: ApiConfig,
    private val endpoint: String = "https://www.bing.com/tspellcheckv3"
) : SpellChecker {

    override val id: String = "bing-spell-checker"
    override val name: String = "Bing Spell Checker"
    override val version: String = "1.1.0"

    override val supportedLanguages: SupportedLanguages
        get() = SupportedLanguages.Specific(languageMapper.spellCheckLanguageCodes.toSet())

    private val parser = createJsonParser<BingSpellCheckResponse>(pluginContext)

    companion object {
        private const val SPELLCHECK_URL = "https://www.bing.com/tspellcheckv3"
        private const val MAX_CHUNK_LENGTH = 1000
    }

    override suspend fun check(request: SpellCheckRequest): Result<SpellCheckResponse, ServiceError> =
        coroutineScope {
            when {
                request.text.isBlank() -> Ok(SpellCheckResponse(request.text, emptyList()))
                else -> checkWithChunking(request)
            }
        }

    private suspend fun checkWithChunking(request: SpellCheckRequest): Result<SpellCheckResponse, ServiceError> =
        coroutineBinding {
            val auth = authManager.getAuth().bind()
            val language = languageMapper.toProviderCode(request.language)
            val chunks = partitionText(request.text)

            val correctedChunks = chunks.map { chunk ->
                if (chunk.isBlank()) chunk else checkChunk(chunk, language, auth).bind()
            }
            val correctedText = correctedChunks.joinToString("")
            SpellCheckResponse(
                correctedText = correctedText,
                corrections = generateCorrections(original = request.text, correctedText)
            )
        }

    private suspend fun checkChunk(
        text: String,
        language: String,
        auth: BingAuth
    ): Result<String, ServiceError> = coroutineBinding {
        val formData = mapOf(
            "text" to text,
            "fromLang" to language,
            "token" to auth.token,
            "key" to auth.key
        )

        val responseString = httpClient.postForm(
            url = endpoint,
            headers = apiConfig.createHeaders(),
            formData = formData,
            queryParams = mapOf(
                "isVertical" to 1,
                "IG" to auth.ig,
                "IID" to auth.iid,
                "SFX" to 2
            ),
            cookies = mapOf("MUID" to auth.muid)
        ).bind()

        val response = parser.parse(responseString).bind()
        response.correctedText.ifEmpty { text }
    }

    private fun partitionText(text: String): List<String> = textChunks(text, MAX_CHUNK_LENGTH)

    fun generateCorrections(original: String, corrected: String): List<Correction> {
        val originalWords = Regex("\\S+").findAll(original).toList()
        val correctedWords = Regex("\\S+").findAll(corrected).toList()
        val patch = DiffUtils.diff(originalWords.map { it.value }, correctedWords.map { it.value })
        return patch.deltas.mapNotNull { delta ->
            if (delta.type != DeltaType.CHANGE && delta.type != DeltaType.DELETE) return@mapNotNull null
            val position = delta.source.position
            val count = delta.source.lines.size
            if (count == 0) return@mapNotNull null
            val start = originalWords[position].range.first
            val end = originalWords[position + count - 1].range.last + 1
            Correction(
                original = original.substring(start, end), startIndex = start, endIndex = end,
                suggestions = if (delta.type == DeltaType.DELETE) emptyList() else listOf(delta.target.lines.joinToString(" ")),
                type = CorrectionType.SPELLING
            )
        }.sortedBy { it.startIndex }
    }
}
