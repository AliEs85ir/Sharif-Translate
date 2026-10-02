package org.shariftranslate.core.updater.data

/**
 * The outcome of comparing the latest remote release against the currently
 * running application version.
 *
 * Produced by [org.shariftranslate.core.updater.Updater.checkForUpdate]
 * and consumed by whatever UI or background task initiates the update check.
 */
sealed interface UpdateCheckResult {

    /**
     * A newer version is available for download.
     *
     * @property info Full metadata about the available release.
     */
    data class UpdateAvailable(val info: VersionInfo) : UpdateCheckResult

    /**
     * The running version is already up to date.
     *
     * @property currentVersion The version string the check was run against.
     */
    data class AlreadyUpToDate(val currentVersion: String) : UpdateCheckResult
}