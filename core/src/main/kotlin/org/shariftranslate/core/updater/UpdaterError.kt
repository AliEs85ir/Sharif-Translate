package org.shariftranslate.core.updater

/**
 * Typed errors that can occur during an update check.
 * Consistent with [org.shariftranslate.api.plugin.ServiceError]
 * and [org.shariftranslate.core.settings.data.SettingsError].
 */
sealed interface UpdaterError {
    val message: String
    val cause: Throwable?

    data class NotConfigured(
        override val message: String = "No update repository configured for Sharif Translate",
        override val cause: Throwable? = null
    ) : UpdaterError

    /** A network connectivity or HTTP error occurred while contacting the release API. */
    data class NetworkError(
        override val message: String,
        override val cause: Throwable? = null
    ) : UpdaterError

    /** The API response could not be parsed. May indicate an API contract change. */
    data class ParseError(
        override val message: String,
        override val cause: Throwable? = null
    ) : UpdaterError

    /** An unexpected error occurred. */
    data class UnknownError(
        override val message: String,
        override val cause: Throwable? = null
    ) : UpdaterError
}