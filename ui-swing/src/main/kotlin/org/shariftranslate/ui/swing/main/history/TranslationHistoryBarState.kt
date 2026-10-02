package org.shariftranslate.ui.swing.main.history

import org.shariftranslate.core.shared.arch.UiState

data class TranslationHistoryBarState(
    val statusText: String,
    val canGoBackward: Boolean,
    val canGoForward: Boolean,
    val isLoading: Boolean,
    val strings: TranslationHistoryBarStrings,
) : UiState

data class TranslationHistoryBarStrings(
    val backwardTooltip: String,
    val forwardTooltip: String,
    val imageTranslateTooltip: String,
)