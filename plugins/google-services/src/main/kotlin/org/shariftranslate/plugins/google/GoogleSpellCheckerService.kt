package org.shariftranslate.plugins.google

import org.shariftranslate.api.language.LanguageCode
import org.shariftranslate.api.plugin.*
import org.shariftranslate.api.spellchecker.*
import org.shariftranslate.plugins.common.*
import org.shariftranslate.plugins.google.common.GoogleLanguageMapper
import org.shariftranslate.plugins.google.common.TranslateResponse
import com.github.michaelbull.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.LinkedHashMap

/** Checks bounded batches while preserving the original layout and UTF-16 offsets. */
class GoogleSpellCheckerService(
    pluginContext: PluginContext,
    private val httpClient: KtorHttpClient,
    private val languageMapper: GoogleLanguageMapper,
    private val apiConfig: ApiConfig,
    private val endpoint: String = "https://translate.googleapis.com/translate_a/single"
) : SpellChecker {
    override val id = "google-spell-checker"
    override val name = "Google Spell Checker"
    override val version = "1.1.0"
    override val supportedLanguages = SupportedLanguages.Dynamic
    override suspend fun fetchSupportedLanguages() = languageMapper.getSupportedLanguages()

    private val parser = createJsonParser<TranslateResponse>(pluginContext)
    private val networkSlots = Semaphore(4)
    private data class CacheKey(val language: LanguageCode, val text: String)
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<CacheKey, SpellCheckResponse>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, SpellCheckResponse>?) = size > 200
    })

    override suspend fun check(request: SpellCheckRequest): Result<SpellCheckResponse, ServiceError> = coroutineScope {
        val spans = sentenceSpans(request.text)
        val output = StringBuilder()
        val corrections = mutableListOf<Correction>()
        var cursor = 0
        // Do not allocate a coroutine for every sentence in a large document.
        for (batch in spans.chunked(4)) {
            val results = batch.map { span -> async {
                val text = request.text.substring(span)
                val key = CacheKey(request.language, text)
                val cached = cache[key]
                if (cached != null) Ok(cached) else networkSlots.withPermit {
                    checkSentence(text, request.language).onSuccess { cache[key] = it }
                }
            } }.awaitAll()
            for ((span, result) in batch.zip(results)) {
                val value = result.getOrElse { return@coroutineScope Err(it) }
                output.append(request.text, cursor, span.first).append(value.correctedText)
                corrections += value.corrections.map { it.copy(startIndex = span.first + it.startIndex, endIndex = span.first + it.endIndex) }
                cursor = span.last + 1
            }
        }
        output.append(request.text, cursor, request.text.length)
        Ok(SpellCheckResponse(output.toString(), corrections))
    }

    private suspend fun checkSentence(text: String, language: LanguageCode): Result<SpellCheckResponse, ServiceError> {
        val body = googleWebRequest(httpClient, endpoint, apiConfig.createHeaders(), mapOf(
            "client" to "gtx", "dj" to 1, "sl" to languageMapper.toProviderCode(language),
            "tl" to "zu", "q" to text, "dt" to "qc"
        )).getOrElse { return Err(it) }
        return parser.parse(body).map { response ->
            val corrected = response.spell?.correctedText?.takeIf { it.isNotBlank() } ?: text
            val originalWords = Regex("\\S+").findAll(text).toList()
            val correctedWords = Regex("\\S+").findAll(corrected).toList()
            // Token insertions/deletions cannot safely be mapped by position.
            val corrections = if (originalWords.size != correctedWords.size) emptyList() else
                originalWords.zip(correctedWords).mapNotNull { (original, replacement) ->
                    if (original.value == replacement.value) null else Correction(
                        original = original.value, startIndex = original.range.first, endIndex = original.range.last + 1,
                        suggestions = listOf(replacement.value), type = CorrectionType.SPELLING,
                        message = "Google spell suggestion"
                    )
                }
            SpellCheckResponse(corrected, corrections)
        }
    }
}

/** Spans exclude separators, so no-correction output is byte-for-byte identical. */
internal fun sentenceSpans(text: String): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    var start = 0
    fun add(end: Int) {
        var first = start
        var last = end - 1
        while (first <= last && text[first].isWhitespace()) first++
        while (last >= first && text[last].isWhitespace()) last--
        if (first <= last) spans += first..last
    }
    for (separator in Regex("(?<=[.!?])\\s+|\\r?\\n+").findAll(text)) {
        add(separator.range.first)
        start = separator.range.last + 1
    }
    add(text.length)
    return spans
}
