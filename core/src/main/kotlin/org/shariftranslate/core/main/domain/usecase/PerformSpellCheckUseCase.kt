package org.shariftranslate.core.main.domain.usecase

import org.shariftranslate.api.core.Logger
import org.shariftranslate.api.plugin.NotificationType
import org.shariftranslate.api.spellchecker.Correction
import org.shariftranslate.api.spellchecker.SpellCheckRequest
import org.shariftranslate.api.spellchecker.SpellChecker
import org.shariftranslate.core.main.mvi.MainState
import org.shariftranslate.core.settings.data.ActiveServiceManager
import org.shariftranslate.core.shared.StatusCode
import org.shariftranslate.core.shared.arch.ServiceType
import org.shariftranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

class PerformSpellCheckUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("PerformSpellCheckUseCase")

    private companion object {
        const val SPELL_CHECK_TIMEOUT_MS = 10_000L
    }

    /**
     * Spell-checks [text] in the source language from [currentState].
     *
     * Returns an empty list silently when no spell checker is active — spell check
     * is an optional enhancement, not a critical operation, so failures should not
     * interrupt the main translation flow.
     *
     * @param currentState Used to pass [MainState.sourceLanguage] as a language hint
     *   to the spell checker service.
     */
    suspend operator fun invoke(
        currentState: MainState,
        text: String,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit
    ): List<Correction> {
        if (text.isBlank()) {
            logger.debug("Spell check skipped: text is blank")
            return emptyList()
        }

        val spellChecker = activeServiceManager.getActiveService<SpellChecker>(ServiceType.SPELL_CHECKER)
        if (spellChecker == null) {
            logger.debug("No spell checker service available — skipping")
            return emptyList()
        }

        logger.debug("Performing spell check with '${spellChecker.name}'")

        // Pass sourceLanguage as a hint — falls back to AUTO if sourceLanguage is AUTO,
        // which most spell checkers handle via language auto-detection.
        val request = SpellCheckRequest(text = text, language = currentState.sourceLanguage)

        val result = try { withTimeoutOrNull(SPELL_CHECK_TIMEOUT_MS) {
            spellChecker.check(request)
        } } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Service threw an exception", e)
            onStatusUpdate(StatusCode.SpellCheckFailed(e.message?.take(120) ?: "Service failed"), NotificationType.ERROR, true)
            return emptyList()
        }

        if (result == null) {
            logger.warn("Spell check timed out after ${SPELL_CHECK_TIMEOUT_MS}ms")
            onStatusUpdate(StatusCode.SpellCheckTimeout, NotificationType.WARNING, true)
            return emptyList()
        }

        return result.fold(
            success = { response ->
                logger.debug("Spell check found ${response.corrections.size} correction(s)")
                response.corrections
            },
            failure = { error ->
                logger.error("Spell check failed: ${error.message}", error.cause)
                val summary = error.message?.lines()?.firstOrNull()?.take(120) ?: "Unknown error"
                onStatusUpdate(StatusCode.SpellCheckFailed(summary), NotificationType.WARNING, true)
                emptyList()
            }
        )
    }
}