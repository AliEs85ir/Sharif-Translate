package org.shariftranslate.ui.swing.main.languagebar

import org.shariftranslate.api.language.LanguageCode
import org.shariftranslate.core.shared.arch.UiState

data class LanguageSelectionBarState(
    val isLoading: Boolean,
    val canClear: Boolean,
    val canSwap: Boolean,
    val allSourceLanguages: List<LanguageCode>,
    val allTargetLanguages: List<LanguageCode>,
    val selectedSourceLanguage: LanguageCode,
    val detectedSourceLanguage: LanguageCode? = null,
    val selectedTargetLanguage: LanguageCode,
    val strings: LanguageSelectionBarStrings
) : UiState

data class LanguageSelectionBarStrings(
    val translateButtonText: String,
    val cancelButtonText: String,
    val clearTooltip: String,
    val swapTooltip: String
)