package org.shariftranslate.core.settings.mvi

import org.shariftranslate.api.plugin.NotificationType
import org.shariftranslate.core.shared.arch.UiEvent

/**
 * One-shot events emitted by [SettingsStore] to be consumed exactly once by the UI.
 */
sealed interface SettingsEvent : UiEvent {

    /**
     * Instructs the UI to close the settings dialog.
     * Emitted after [SettingsIntent.CancelChanges].
     */
    data object CloseSettingsDialog : SettingsEvent

    /**
     * Instructs the UI to display a transient message to the user.
     */
    data class ShowMessage(
        val message: String,
        val type: NotificationType
    ) : SettingsEvent
}