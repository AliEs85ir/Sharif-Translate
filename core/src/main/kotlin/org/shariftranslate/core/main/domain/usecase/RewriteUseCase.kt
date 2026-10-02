package org.shariftranslate.core.main.domain.usecase

import org.shariftranslate.api.core.Logger
import org.shariftranslate.api.plugin.NotificationType
import org.shariftranslate.api.rewriter.RewriteRequest
import org.shariftranslate.api.rewriter.Rewriter
import org.shariftranslate.core.settings.data.ActiveServiceManager
import org.shariftranslate.core.settings.data.Configuration
import org.shariftranslate.core.shared.AppConstants
import org.shariftranslate.core.shared.StatusCode
import org.shariftranslate.core.shared.arch.ServiceType
import org.shariftranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.withTimeoutOrNull

class RewriteUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("RewriteUseCase")

    suspend operator fun invoke(
        text: String,
        config: Configuration,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit
    ): String {
        val rewriter = activeServiceManager.getActiveService<Rewriter>(ServiceType.REWRITER)
        if (rewriter == null) {
            logger.warn("No rewriter service available")
            onStatusUpdate(StatusCode.NoRewriterActive, NotificationType.WARNING, true)
            return ""
        }

        onStatusUpdate(StatusCode.Rewriting, NotificationType.INFO, false)

        val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
            rewriter.rewrite(
                RewriteRequest(
                    text  = text,
                    style = config.rewriteStyle
                )
            )
        }

        if (result == null) {
            logger.error("Rewrite timed out")
            onStatusUpdate(StatusCode.RewriteTimeout, NotificationType.ERROR, true)
            return ""
        }

        return result.fold(
            success = { response ->
                logger.debug("Rewrite successful")
                onStatusUpdate(StatusCode.RewriteReady, NotificationType.SUCCESS, true)
                response.rewrittenText
            },
            failure = { error ->
                logger.error("Rewrite failed: ${error.message}", error.cause)
                val summary = error.message?.lines()?.firstOrNull()?.take(120) ?: "Unknown error"
                onStatusUpdate(StatusCode.RewriteFailed(summary), NotificationType.ERROR, true)
                ""
            }
        )
    }
}