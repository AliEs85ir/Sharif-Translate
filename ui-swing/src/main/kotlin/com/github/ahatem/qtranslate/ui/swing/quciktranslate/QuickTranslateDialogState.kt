package com.github.ahatem.qtranslate.ui.swing.quciktranslate

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.core.shared.arch.UiState

/**
 * An immutable snapshot of all data required to render the QuickTranslateDialog.
 */
data class QuickTranslateDialogState(
    val isVisible: Boolean,
    val isLoading: Boolean,
    val translatedText: String,
    val sourceText: String,
    val isPinned: Boolean,
    val isFavorite: Boolean,
    val collections: List<CollectionChoice>,

    // --- The Data is now a first-class citizen ---
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,
    val availableLanguages: List<LanguageCode>,

    val translatorSelectorState: QuickTranslateSelectorState,
    val actionsState: QuickTranslateActionsState,
    val config: DialogConfig,
    val strings: DialogStrings
) : UiState

data class CollectionChoice(val id: String, val name: String, val included: Boolean)

/**
 * Configuration for how the dialog should behave physically. This is a subset of your main Configuration.
 */
data class DialogConfig(
    val font: FontConfig,
    val fallbackFont: FontConfig,
    val autoSizeEnabled: Boolean,
    val autoPositionEnabled: Boolean,
    val transparencyPercentage: Int,
    val idleTimeoutSeconds: Int = 3,
    val lastKnownSize: Size,
    val lastKnownPosition: Position
)

/** State for the translator selector within the dialog. */
data class QuickTranslateSelectorState(
    val availableTranslators: List<ServiceInfo>,
    val selectedTranslatorId: String?,
)

/** State for the action buttons in the dialog's title bar. */
data class QuickTranslateActionsState(
    val canCopy: Boolean,
    val canListen: Boolean
)

/** All user-facing strings for the dialog. */
data class DialogStrings(
    val copyTooltip: String,
    val listenTooltip: String,
    val pinTooltip: String,
    val unpinTooltip: String,
    val loadingText: String,
    val more: String,
    val less: String,
    val favorite: String,
    val removeFavorite: String,
    val collection: String,
    val manageCollections: String,
    val newCollection: String,
    val swap: String,
    val autoDetect: String,
    val original: String,
    val editHint: String,
    val translator: String
)
