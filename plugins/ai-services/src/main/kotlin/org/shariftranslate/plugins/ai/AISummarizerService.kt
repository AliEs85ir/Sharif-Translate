package org.shariftranslate.plugins.ai

import org.shariftranslate.api.plugin.ServiceError
import org.shariftranslate.api.plugin.SupportedLanguages
import org.shariftranslate.api.summarizer.SummarizeRequest
import org.shariftranslate.api.summarizer.SummarizeResponse
import org.shariftranslate.api.summarizer.Summarizer
import org.shariftranslate.api.summarizer.SummaryLength
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map

class AISummarizerService(
    private val client: AIServiceClient
) : Summarizer {

    override val id: String = "ai-summarizer"
    override val name: String = "AI Summarizer"
    override val version: String = "1.0.0"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    override suspend fun summarize(request: SummarizeRequest): Result<SummarizeResponse, ServiceError> {
        val lengthInstruction = when (request.length) {
            SummaryLength.SHORT -> "Respond with a single sentence of no more than 30 words."
            SummaryLength.MEDIUM -> "Respond with 2–4 concise sentences that capture the key points."
            SummaryLength.LONG -> "Respond with a detailed multi-paragraph summary that preserves important nuance."
        }

        val system = """
        You are a professional editor. 
        
        TASK:
        Summarize the text provided by the user. 
        The summary MUST be written in the same language as the source text itself.
        
        CONSTRAINTS:
        - $lengthInstruction
        - Output ONLY the summarized text.
        - Do not include any introductory text, labels, quotes, or conversational filler (e.g., do NOT say "Here is a summary").
        
        The user's text to be summarized starts after the '---' delimiter below.
        ---
    """.trimIndent()

        return client.complete(system, request.text).map { summary ->
            SummarizeResponse(summary = summary.trim().removeSurrounding("\""))
        }
    }
}