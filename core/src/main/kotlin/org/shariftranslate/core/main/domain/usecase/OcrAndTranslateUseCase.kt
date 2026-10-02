package org.shariftranslate.core.main.domain.usecase

import org.shariftranslate.api.core.Logger
import org.shariftranslate.api.ocr.ImageData
import org.shariftranslate.api.ocr.OCR
import org.shariftranslate.api.ocr.OCRRequest
import org.shariftranslate.api.plugin.NotificationType
import org.shariftranslate.core.main.mvi.MainState
import org.shariftranslate.core.settings.data.ActiveServiceManager
import org.shariftranslate.core.shared.StatusCode
import org.shariftranslate.core.shared.arch.ServiceType
import org.shariftranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

class OcrAndTranslateUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("OcrAndTranslateUseCase")

    private companion object {
        const val OCR_TIMEOUT_MS = 30_000L
    }

    /**
     * Performs OCR on [image] and returns the extracted text.
     *
     * Returns an empty string on any failure. The [MainStore] that calls this
     * should update [MainState.inputText] with the result and trigger translation
     * only if the returned string is non-blank.
     */
    suspend operator fun invoke(
        image: ImageData,
        currentState: MainState,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit
    ): String {
        val ocrService = activeServiceManager.getActiveService<OCR>(ServiceType.OCR)
        if (ocrService == null) {
            logger.warn("No OCR service available")
            onStatusUpdate(StatusCode.NoOcrServiceActive, NotificationType.ERROR, true)
            return ""
        }

        logger.info("Starting OCR with '${ocrService.name}'")
        onStatusUpdate(StatusCode.RecognizingText, NotificationType.INFO, false)

        val request = OCRRequest(image, language = currentState.sourceLanguage)
        logger.debug("OCR request: language=${currentState.sourceLanguage}")

        val result = try { withTimeoutOrNull(OCR_TIMEOUT_MS) {
            ocrService.extractText(request)
        } } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Service threw an exception", e)
            onStatusUpdate(StatusCode.OcrFailed(e.message?.take(120) ?: "Service failed"), NotificationType.ERROR, true)
            return ""
        }

        if (result == null) {
            logger.error("OCR timed out after ${OCR_TIMEOUT_MS}ms")
            onStatusUpdate(StatusCode.OcrTimeout, NotificationType.ERROR, true)
            return ""
        }

        return result.fold(
            success = { response ->
                if (response.text.isBlank()) {
                    logger.warn("No text detected in image")
                    onStatusUpdate(StatusCode.NoTextInImage, NotificationType.WARNING, true)
                    ""
                } else {
                    logger.info("OCR successful: detected ${response.text.length} characters")
                    onStatusUpdate(StatusCode.OcrComplete, NotificationType.SUCCESS, true)
                    response.text
                }
            },
            failure = { error ->
                logger.error("OCR failed: ${error.message}", error.cause)
                val summary = error.message?.lines()?.firstOrNull()?.take(120) ?: "Unknown error"
                onStatusUpdate(StatusCode.OcrFailed(summary), NotificationType.ERROR, true)
                ""
            }
        )
    }
}