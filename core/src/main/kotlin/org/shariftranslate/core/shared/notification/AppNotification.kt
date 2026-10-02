package org.shariftranslate.core.shared.notification

import org.shariftranslate.api.plugin.NotificationType

data class AppNotification(
    val type: NotificationType,
    val code: NotificationCode,
    val sourcePluginId: String? = null,
    /** Wall-clock time when this notification was posted, used to discard stale replays. */
    val timestamp: Long = System.currentTimeMillis()
)
