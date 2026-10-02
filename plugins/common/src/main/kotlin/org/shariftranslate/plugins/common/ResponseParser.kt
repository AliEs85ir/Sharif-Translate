package org.shariftranslate.plugins.common

import org.shariftranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * Parses API responses into a specific response type.
 */
interface ResponseParser<T> {
    suspend fun parse(jsonString: String): Result<T, ServiceError>
}