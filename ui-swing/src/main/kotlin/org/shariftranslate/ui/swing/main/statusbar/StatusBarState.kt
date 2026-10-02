package org.shariftranslate.ui.swing.main.statusbar

import org.shariftranslate.api.plugin.NotificationType
import org.shariftranslate.core.shared.arch.UiState

data class StatusBarState(
    val message: String,
    val type: NotificationType,
    val isLoading: Boolean = false,
    val notificationTooltip: String,
    val isNotificationButtonEnabled: Boolean
) : UiState

