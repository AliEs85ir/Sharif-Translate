package org.shariftranslate.plugins.ai

import org.shariftranslate.api.ocr.ImageData
import org.shariftranslate.api.plugin.PluginContext
import org.shariftranslate.api.plugin.ServiceError
import org.shariftranslate.plugins.common.KtorHttpClient
import org.shariftranslate.plugins.common.createJsonParser
import com.github.michaelbull.result.*
import kotlinx.serialization.json.*
import java.net.URI
import java.util.Base64
import java.util.Locale

/** Text and vision share validation, user-owned credentials and response handling. */
class AIServiceClient(
    private val pluginContext: PluginContext,
    private val httpClient: KtorHttpClient,
    private val settings: () -> AISettings
) {
    private val responseParser = createJsonParser<ChatCompletionResponse>(pluginContext)

    suspend fun complete(system: String, userContent: String): Result<String, ServiceError> {
        val current = settings().copy()
        val connection = prepare(current).getOrElse { return Err(it) }
        return send(connection, ChatCompletionRequest(
            model = current.model.trim(),
            messages = listOf(ChatMessage("system", system), ChatMessage("user", userContent)),
            temperature = current.temperature, maxCompletionTokens = current.maxTokens
        ))
    }

    suspend fun completeWithImage(system: String, image: ImageData, userText: String = ""): Result<String, ServiceError> {
        val current = settings().copy()
        val connection = prepare(current).getOrElse { return Err(it) }
        val format = image.format.trim().trimStart('.').lowercase(Locale.ROOT)
        val mime = when (format) {
            "jpg", "jpeg" -> "image/jpeg"
            "png", "gif", "webp" -> "image/$format"
            else -> return Err(ServiceError.InvalidInputError("AI vision requires a PNG, JPEG, GIF or WebP image."))
        }
        if (image.bytes.isEmpty()) return Err(ServiceError.InvalidInputError("AI vision requires a non-empty image."))
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:$mime;base64,${Base64.getEncoder().encodeToString(image.bytes)}") }
            })
            if (userText.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", userText) })
        }
        return send(connection, VisionChatCompletionRequest(
            model = current.model.trim(),
            messages = listOf(VisionMessage("system", JsonPrimitive(system)), VisionMessage("user", content)),
            temperature = current.temperature, maxCompletionTokens = current.maxTokens
        ))
    }

    private data class Connection(val endpoint: String, val headers: Map<String, String>)

    internal fun validateConfiguration(current: AISettings): ServiceError? =
        prepare(current, requireCredentials = false).fold(success = { null }, failure = { it })

    private fun prepare(current: AISettings, requireCredentials: Boolean = true): Result<Connection, ServiceError> {
        fun invalid(message: String) = Err(ServiceError.InvalidInputError("AI Plugin: $message"))
        val uri = kotlin.runCatching { URI(current.baseUrl.trim()) }.getOrNull()
            ?: return invalid("Base URL must be a valid HTTP or HTTPS URL.")
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.port !in -1..65535 || uri.port == 0) {
            return invalid("Base URL must be a valid HTTP or HTTPS URL.")
        }
        if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) {
            return invalid("Base URL must not contain a query, fragment or embedded credentials.")
        }
        if (current.model.isBlank() || !current.temperature.isFinite() || current.temperature !in 0.0..2.0 || current.maxTokens !in 1..131072) {
            return invalid("Check the model, temperature and maximum token settings.")
        }
        val extras = if (current.customHeaders.isBlank()) emptyMap() else kotlin.runCatching {
            Json.decodeFromString<Map<String, String>>(current.customHeaders)
        }.getOrElse { return invalid("Custom Headers must be a JSON object containing string values.") }
        val names = extras.keys.map { it.lowercase(Locale.ROOT) }
        if (names.distinct().size != names.size || extras.any { (key, value) ->
                !HEADER_NAME.matches(key) || value.any { it == '\r' || it == '\n' || it == '\u0000' } ||
                    key.lowercase(Locale.ROOT) in RESERVED_HEADERS
            }) {
            return invalid("Custom Headers contain an invalid, duplicate or reserved header (Authorization, Content-Type or transport headers). Use API Key for bearer authentication.")
        }
        if (current.apiKey.any { it == '\r' || it == '\n' || it == '\u0000' }) return invalid("API Key contains invalid characters.")
        val local = uri.host.lowercase(Locale.ROOT) in setOf("localhost", "127.0.0.1", "[::1]", "::1")
        if (requireCredentials && !local && current.apiKey.isBlank()) return Err(ServiceError.AuthenticationError(
            "AI Plugin: Enter your own API key in Settings → Plugins → AI Services."
        ))
        val headers = extras + mapOf("Content-Type" to "application/json") +
            if (current.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${current.apiKey.trim()}")
        return Ok(Connection("${current.baseUrl.trim().trimEnd('/')}/chat/completions", headers))
    }

    private suspend inline fun <reified T> send(connection: Connection, body: T): Result<String, ServiceError> {
        // Do not log request text, image bytes, API keys, or provider error bodies.
        pluginContext.logger.debug("Sending AI chat request")
        return httpClient.sendJson(connection.endpoint, connection.headers, body)
            .andThen { responseParser.parse(it) }.andThen { extractText(it) }
    }

    private fun extractText(response: ChatCompletionResponse): Result<String, ServiceError> {
        response.error?.let { return Err(mapError(it)) }
        val choice = response.choices.firstOrNull()
            ?: return Err(ServiceError.InvalidResponseError("AI service returned no choices.", null))
        if (choice.finishReason == "length") return Err(ServiceError.InvalidResponseError(
            "AI response was truncated. Increase Max Tokens or shorten the input.", null
        ))
        if (choice.finishReason == "content_filter" || !choice.message.refusal.isNullOrBlank()) {
            return Err(ServiceError.InvalidResponseError("AI service declined this request.", null))
        }
        return choice.message.content?.takeIf { it.isNotBlank() }?.let { Ok(it) }
            ?: Err(ServiceError.InvalidResponseError("AI service returned empty text.", null))
    }

    private fun mapError(error: ChatError): ServiceError {
        val msg = error.message
        fun mentions(vararg terms: String) = terms.any { msg.contains(it, ignoreCase = true) }
        return when {
            error.code in setOf("invalid_api_key", "401", "403") || mentions("api key", "invalid key", "unauthorized", "authentication") -> ServiceError.AuthenticationError(msg)
            error.code in setOf("rate_limit_exceeded", "429") || mentions("rate limit", "quota", "too many requests") -> ServiceError.RateLimitError(msg)
            error.code in setOf("service_unavailable", "server_error", "503", "529") || mentions("unavailable", "overloaded", "capacity") -> ServiceError.ServiceUnavailableError(msg)
            mentions("invalid request", "bad request", "does not support") -> ServiceError.InvalidInputError(msg)
            else -> ServiceError.UnknownError(msg)
        }
    }

    private companion object {
        val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        val RESERVED_HEADERS = setOf("authorization", "content-type", "host", "content-length", "transfer-encoding", "connection")
    }
}
