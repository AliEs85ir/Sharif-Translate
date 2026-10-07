package org.shariftranslate.plugins.common

import org.shariftranslate.api.plugin.PluginContext
import org.shariftranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Generic JSON response parser using Kotlinx Serialization.
 */
class JsonResponseParser<T>(
    private val pluginContext: PluginContext,
    private val deserializer: (String) -> T,
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
) : ResponseParser<T> {

    override suspend fun parse(jsonString: String): Result<T, ServiceError> {
        return try {
            Ok(deserializer(jsonString))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            pluginContext.logger.warn("Service returned invalid JSON")
            Err(ServiceError.InvalidResponseError("Service returned invalid JSON", e))
        } catch (e: Exception) {
            pluginContext.logger.warn("Service response parsing failed")
            Err(ServiceError.UnknownError("Service response parsing failed", e))
        }
    }
}

/**
 * Creates a JsonResponseParser for inline reified types.
 */
inline fun <reified T> createJsonParser(
    pluginContext: PluginContext,
    json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
): JsonResponseParser<T> {
    return JsonResponseParser(
        pluginContext = pluginContext,
        deserializer = { jsonString -> json.decodeFromString<T>(jsonString) },
        json = json
    )
}

inline fun <reified T> JsonResponseParser<T>.jsonEncodeToString(value: T): String = json.encodeToString(value)
