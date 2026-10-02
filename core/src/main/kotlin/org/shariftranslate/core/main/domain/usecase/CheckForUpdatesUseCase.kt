package org.shariftranslate.core.main.domain.usecase

import org.shariftranslate.api.core.Logger
import org.shariftranslate.api.plugin.NotificationType
import org.shariftranslate.core.settings.data.Configuration
import org.shariftranslate.core.shared.StatusCode
import org.shariftranslate.core.shared.logging.LoggerFactory
import org.shariftranslate.core.shared.notification.AppNotification
import org.shariftranslate.core.shared.notification.NotificationBus
import org.shariftranslate.core.shared.notification.NotificationCode
import org.shariftranslate.core.updater.Updater
import org.shariftranslate.core.updater.UpdaterError
import org.shariftranslate.core.updater.data.UpdateCheckResult
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import kotlinx.coroutines.flow.StateFlow

/**
 * Checks whether a newer version of the application is available.
 *
 * Respects the [Configuration.autoCheckForUpdates] setting unless [force] is `true`.
 * On success, posts a notification and calls [onStatusUpdate] with the result.
 * On failure, maps the typed [UpdaterError] to a user-friendly message.
 *
 * @property currentVersion The version string of the running application (e.g. `"1.2.0"`).
 *   Passed to [Updater.checkForUpdate] for semantic version comparison.
 */
class CheckForUpdatesUseCase(
    private val currentVersion: String,
    private val settingsState: StateFlow<Configuration>,
    private val updater: Updater,
    private val notificationBus: NotificationBus,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("CheckForUpdatesUseCase")

    /**
     * Performs the update check.
     *
     * @param onStatusUpdate Callback for displaying a status message in the UI.
     * @param force If `true`, skips the [Configuration.autoCheckForUpdates] guard.
     */
    suspend operator fun invoke(
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit,
        force: Boolean = false
    ) {
        if (!force && !settingsState.value.autoCheckForUpdates) {
            logger.debug("Auto-update check disabled — skipping")
            return
        }

        logger.info("Checking for updates (currentVersion=$currentVersion, force=$force)")

        updater.checkForUpdate(currentVersion)
            .onOk { result ->
                when (result) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        val info = result.info
                        logger.info("Update available: ${info.versionTag}")

                        notificationBus.post(
                            AppNotification(
                                type = NotificationType.INFO,
                                code = NotificationCode.UpdateAvailable(
                                    newVersion = info.releaseName,
                                    currentVersion = currentVersion,
                                    releaseNotes = info.releaseNotes,
                                    downloadUrl = info.downloadUrl,
                                    releaseUrl = info.releaseUrl
                                )
                            )
                        )
                    }

                    is UpdateCheckResult.AlreadyUpToDate -> {
                        logger.info("Already up to date (version: $currentVersion)")
                        onStatusUpdate(StatusCode.AlreadyUpToDate(currentVersion), NotificationType.SUCCESS, true)
                    }
                }
            }
            .onErr { error ->
                logger.error("Update check failed: ${error.message}", error.cause)

                val code = when (error) {
                    is UpdaterError.NotConfigured -> StatusCode.UpdateSourceNotConfigured
                    is UpdaterError.NetworkError -> StatusCode.UpdateCheckNetworkError
                    is UpdaterError.ParseError   -> StatusCode.UpdateCheckParseError
                    is UpdaterError.UnknownError -> StatusCode.UpdateCheckUnknownError
                }

                onStatusUpdate(code, NotificationType.ERROR, true)
            }
    }
}