package org.shariftranslate.plugins.common

import org.shariftranslate.api.plugin.PluginContext
import org.shariftranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes a block in the IO dispatcher, handling errors consistently.
 */
suspend fun <T> withIO(block: suspend () -> T): Result<T, ServiceError> = withContext(Dispatchers.IO) {
    try {
        Ok(block())
    } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        Err(ServiceError.UnknownError("Operation failed: ${e.message}", e))
    }
}

/**
 * Logs and handles errors for a specific operation.
 */
suspend fun <T> PluginContext.logAndHandleError(
    operation: String,
    block: suspend () -> T
): Result<T, ServiceError> = withContext(Dispatchers.IO) {
    try {
        Ok(block())
    } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        logger.error("$operation failed", e)
        Err(ServiceError.UnknownError("Failed to $operation: ${e.message}", e))
    }
}