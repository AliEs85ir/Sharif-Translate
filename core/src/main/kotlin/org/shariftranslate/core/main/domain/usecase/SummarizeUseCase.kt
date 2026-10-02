package org.shariftranslate.core.main.domain.usecase

import org.shariftranslate.api.core.Logger
import org.shariftranslate.api.plugin.NotificationType
import org.shariftranslate.api.summarizer.SummarizeRequest
import org.shariftranslate.api.summarizer.Summarizer
import org.shariftranslate.core.settings.data.ActiveServiceManager
import org.shariftranslate.core.settings.data.Configuration
import org.shariftranslate.core.shared.AppConstants
import org.shariftranslate.core.shared.StatusCode
import org.shariftranslate.core.shared.arch.ServiceType
import org.shariftranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.withTimeoutOrNull

class SummarizeUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("SummarizeUseCase")

    suspend operator fun invoke(
        text: String,
        config: Configuration,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit
    ): String {
        val summarizer = activeServiceManager.getActiveService<Summarizer>(ServiceType.SUMMARIZER)
        if (summarizer == null) {
            logger.warn("No summarizer service available")
            onStatusUpdate(StatusCode.NoSummarizerActive, NotificationType.WARNING, true)
            return ""
        }

        onStatusUpdate(StatusCode.Summarizing, NotificationType.INFO, false)

        val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
            summarizer.summarize(
                SummarizeRequest(
                    text   = text,
                    length = config.summaryLength
                )
            )
        }

        if (result == null) {
            logger.error("Summarize timed out")
            onStatusUpdate(StatusCode.SummarizeTimeout, NotificationType.ERROR, true)
            return ""
        }

        return result.fold(
            success = { response ->
                logger.debug("Summarize successful")
                onStatusUpdate(StatusCode.SummaryReady, NotificationType.SUCCESS, true)
                response.summary
            },
            failure = { error ->
                logger.error("Summarize failed: ${error.message}", error.cause)
                val summary = error.message?.lines()?.firstOrNull()?.take(120) ?: "Unknown error"
                onStatusUpdate(StatusCode.SummarizeFailed(summary), NotificationType.ERROR, true)
                ""
            }
        )
    }
}