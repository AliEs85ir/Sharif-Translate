package org.shariftranslate.plugins.common

import org.shariftranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * Abstraction for HTTP requests used by plugins.
 */
interface HttpClient {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<String, ServiceError>

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        queryParams: Map<String, Any?> = emptyMap()
    ): Result<String, ServiceError>
}