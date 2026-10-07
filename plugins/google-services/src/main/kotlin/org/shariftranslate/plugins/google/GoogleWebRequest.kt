package org.shariftranslate.plugins.google

import org.shariftranslate.api.plugin.ServiceError
import org.shariftranslate.plugins.common.KtorHttpClient
import com.github.michaelbull.result.*

/** Keeps the full JSON response when the legacy web client is rate-limited. */
internal suspend fun googleWebRequest(
    httpClient: KtorHttpClient,
    url: String,
    headers: Map<String, String>,
    queryParams: Map<String, Any?>
): Result<String, ServiceError> {
    val primary = httpClient.get(url, headers, queryParams)
    // One explicit fallback, for anonymous Google web requests only; no AI retry.
    return if (primary.getError() is ServiceError.RateLimitError) {
        httpClient.get(url, headers, queryParams + ("client" to "dict-chrome-ex"))
    } else primary
}
