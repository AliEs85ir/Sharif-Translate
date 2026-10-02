package org.shariftranslate.plugins.ai

import org.shariftranslate.api.ocr.ImageData
import org.shariftranslate.api.plugin.PluginContext
import org.shariftranslate.api.plugin.ServiceError
import org.shariftranslate.plugins.common.KtorHttpClient
import org.shariftranslate.plugins.common.createJsonParser
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.toResultOr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import java.net.URI

/**
 * Shared HTTP wrapper for the OpenAI-compatible chat completions API.
 *
 * A single code path handles every supported endpoint:
 * OpenRouter · OpenAI · Mistral · Gemini OpenAI-compat · Ollama · Azure OpenAI · etc.
 *
 * The [baseUrl] is read from [AISettings] on every call so endpoint changes in
 * Settings → Plugins take effect without restarting the app.
 *
 * ### Custom headers
 * [AISettings.customHeaders] is a JSON object string merged into every request.
 * The defaults include OpenRouter's optional site-attribution headers
 * (`HTTP-Referer`, `X-Title`), which are harmless with other providers.
 *
 * @param settings Lambda returning live [AISettings] — picks up changes without rebuild.
 */
class AIServiceClient(
    private val pluginContext: PluginContext,
    private val httpClient: KtorHttpClient,
    private val settings: () -> AISettings
) {
    private val responseParser = createJsonParser<ChatCompletionResponse>(pluginContext)
    private val headersJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Sends a chat-completion request and returns the model's text response.
     *
     * @param system      The system-level instruction (becomes the first "system" message).
     * @param userContent The user turn content.
     */
    suspend fun complete(
        system: String,
        userContent: String
    ): Result<String, ServiceError> {
        val current = settings()

        validateSettings(current)?.let { return Err(it) }
        if (current.apiKey.isBlank() && !isLocalEndpoint(current.baseUrl)) {
            return Err(
                ServiceError.AuthenticationError(
                    "AI Plugin: API key is not configured. Add your key in Settings → Plugins → AI Plugin."
                )
            )
        }

        val baseUrl = current.baseUrl.trim().trimEnd('/')
        val endpoint = "$baseUrl/chat/completions"

        pluginContext.logger.debug("AIServiceClient → $endpoint [model=${current.model}]")

        val requestBody = ChatCompletionRequest(
            model               = current.model,
            messages            = listOf(
                ChatMessage(role = "system", content = system),
                ChatMessage(role = "user",   content = userContent)
            ),
            temperature         = current.temperature,
            maxCompletionTokens = current.maxTokens
        )

        val headers = buildMap<String, String> {
            if (current.apiKey.isNotBlank()) put("Authorization", "Bearer ${current.apiKey.trim()}")
            put("Content-Type",  "application/json")
            // Merge user-supplied custom headers (e.g. OpenRouter attribution headers)
            if (current.customHeaders.isNotBlank()) {
                runCatching {
                    headersJson.decodeFromString<Map<String, String>>(current.customHeaders)
                }.onSuccess { extra ->
                    putAll(extra)
                }.onFailure { e ->
                    pluginContext.logger.warn(
                        "AI Plugin: Could not parse Custom Headers JSON — skipping. Error: ${e.message}"
                    )
                }
            }
        }

        return httpClient.sendJson(
            url         = endpoint,
            headers     = headers,
            body        = requestBody,
            queryParams = emptyMap()
        ).andThen { responseString ->
            responseParser.parse(responseString)
        }.andThen { response ->
            extractText(response)
        }
    }

    /**
     * Sends a vision (multimodal) request: a system prompt + an image + optional text.
     *
     * The image is base64-encoded and embedded as a data URI in the `image_url` content
     * part, which is the format expected by OpenAI-compatible vision endpoints on
     * OpenRouter (GPT-4V, Gemini Vision, Claude Vision, etc.).
     *
     * **Model requirement:** the active model must support vision. Models without vision
     * capability will return an error from the provider — switch to a multimodal model
     * (e.g. `openai/gpt-4o`, `google/gemini-flash-1.5`, `anthropic/claude-3-5-sonnet`).
     *
     * @param system        System-level instruction for the model.
     * @param image         Raw image data to send. Bytes are base64-encoded internally.
     * @param userText      Optional text prompt shown alongside the image in the user turn.
     */
    suspend fun completeWithImage(
        system: String,
        image: ImageData,
        userText: String = ""
    ): Result<String, ServiceError> {
        val current = settings()

        validateSettings(current)?.let { return Err(it) }
        if (current.apiKey.isBlank() && !isLocalEndpoint(current.baseUrl)) {
            return Err(
                ServiceError.AuthenticationError(
                    "AI Plugin: API key is not configured. Add your key in Settings → Plugins → AI Plugin."
                )
            )
        }

        val baseUrl  = current.baseUrl.trim().trimEnd('/')
        val endpoint = "$baseUrl/chat/completions"

        pluginContext.logger.debug(
            "AIServiceClient (vision) → $endpoint [model=${current.model}, image=${image.width}×${image.height} ${image.format}]"
        )

        // Build the user content array: image part + optional text part
        val mimeType    = "image/${image.format.lowercase().trimStart('.')}"
        val base64Image = Base64.getEncoder().encodeToString(image.bytes)
        val dataUri     = "data:$mimeType;base64,$base64Image"

        val userContent = buildJsonArray {
            add(buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", dataUri) }
            })
            if (userText.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", userText)
                })
            }
        }

        val requestBody = VisionChatCompletionRequest(
            model               = current.model,
            messages            = listOf(
                VisionMessage(role = "system", content = JsonPrimitive(system)),
                VisionMessage(role = "user",   content = userContent)
            ),
            temperature         = current.temperature,
            maxCompletionTokens = current.maxTokens
        )

        val headers = buildMap<String, String> {
            if (current.apiKey.isNotBlank()) put("Authorization", "Bearer ${current.apiKey.trim()}")
            put("Content-Type",  "application/json")
            if (current.customHeaders.isNotBlank()) {
                runCatching {
                    headersJson.decodeFromString<Map<String, String>>(current.customHeaders)
                }.onSuccess { putAll(it) }
                 .onFailure { e ->
                    pluginContext.logger.warn(
                        "AI Plugin: Could not parse Custom Headers JSON — skipping. Error: ${e.message}"
                    )
                }
            }
        }

        return httpClient.sendJson(
            url         = endpoint,
            headers     = headers,
            body        = requestBody,
            queryParams = emptyMap()
        ).andThen { responseString ->
            responseParser.parse(responseString)
        }.andThen { response ->
            extractText(response)
        }
    }

    private fun isLocalEndpoint(baseUrl: String): Boolean =
        runCatching { URI(baseUrl.trim()).host?.lowercase() in setOf("localhost", "127.0.0.1", "[::1]", "::1") }
            .getOrDefault(false)

    private fun validateSettings(current: AISettings): ServiceError? {
        val uri = runCatching { URI(current.baseUrl.trim()) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            return ServiceError.InvalidInputError("AI Plugin: Base URL must be a valid HTTP or HTTPS URL.")
        }
        if (uri?.rawQuery != null || uri?.rawFragment != null || uri?.rawUserInfo != null) {
            return ServiceError.InvalidInputError("AI Plugin: Base URL must not contain a query, fragment or embedded credentials.")
        }
        if (current.customHeaders.isNotBlank() && runCatching {
                headersJson.decodeFromString<Map<String, String>>(current.customHeaders)
            }.isFailure) {
            return ServiceError.InvalidInputError("AI Plugin: Custom Headers must be a JSON object containing string values.")
        }
        if (current.model.isBlank() || !current.temperature.isFinite() || current.temperature !in 0.0..2.0 || current.maxTokens <= 0) {
            return ServiceError.InvalidInputError("AI Plugin: Check the model, temperature and maximum token settings.")
        }
        return null
    }

    private fun extractText(response: ChatCompletionResponse): Result<String, ServiceError> {
        response.error?.let { return Err(mapError(it)) }
        val choice = response.choices.firstOrNull()
            ?: return Err(ServiceError.InvalidResponseError("AI service returned no choices.", null))
        if (choice.finishReason == "length") {
            return Err(ServiceError.InvalidResponseError("AI response was truncated. Increase Max Tokens or shorten the input.", null))
        }
        if (choice.finishReason == "content_filter" || !choice.message.refusal.isNullOrBlank()) {
            return Err(ServiceError.InvalidResponseError("AI service declined this request.", null))
        }
        val content = choice.message.content?.takeIf { it.isNotBlank() }
            ?: return Err(ServiceError.InvalidResponseError("AI service returned empty text.", null))
        return Ok(content)
    }

    private fun mapError(error: ChatError): ServiceError {
        val msg = error.message
        return when {
            error.code in AUTH_CODES || msg.containsAny("api key", "invalid key", "unauthorized", "authentication")
                -> ServiceError.AuthenticationError(msg)

            error.code in RATE_LIMIT_CODES || msg.containsAny("rate limit", "quota", "too many requests")
                -> ServiceError.RateLimitError(msg)

            error.code in UNAVAILABLE_CODES || msg.containsAny("unavailable", "overloaded", "capacity")
                -> ServiceError.ServiceUnavailableError(msg)

            msg.containsAny("invalid request", "bad request", "does not support")
                -> ServiceError.InvalidInputError(msg)

            else -> ServiceError.UnknownError(msg)
        }
    }

    private fun String.containsAny(vararg terms: String): Boolean =
        terms.any { this.contains(it, ignoreCase = true) }

    private companion object {
        val AUTH_CODES       = setOf("invalid_api_key", "401", "403")
        val RATE_LIMIT_CODES = setOf("rate_limit_exceeded", "429")
        val UNAVAILABLE_CODES = setOf("service_unavailable", "server_error", "503", "529")
    }
}
