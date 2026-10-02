package org.shariftranslate.api.plugin

/**
 * Defines the severity level of a notification shown to the user.
 */
enum class NotificationType {
    /** General information. */
    INFO,

    /** A potential issue that does not prevent functionality. */
    WARNING,

    /** An error that has occurred. */
    ERROR,

    /** Confirmation of a successful operation. */
    SUCCESS
}