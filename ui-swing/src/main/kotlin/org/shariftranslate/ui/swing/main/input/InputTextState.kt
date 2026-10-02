package org.shariftranslate.ui.swing.main.input

import org.shariftranslate.api.spellchecker.Correction
import org.shariftranslate.core.settings.data.FontConfig
import org.shariftranslate.core.shared.arch.UiState
import org.shariftranslate.ui.swing.main.widgets.TextActionsState

data class InputTextState(
    val text: String,
    val corrections: List<Correction>,
    val fontConfig: FontConfig,
    val fallbackFontConfig: FontConfig,
    val isEditable: Boolean,
    val isLoading: Boolean,
    val actionsState: TextActionsState
) : UiState