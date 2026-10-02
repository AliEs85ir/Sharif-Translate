package org.shariftranslate.ui.swing.dictionary

import org.shariftranslate.api.dictionary.DictionaryEntry
import org.shariftranslate.core.main.domain.model.ServiceInfo
import org.shariftranslate.core.settings.data.DictionaryAutoSource

data class DictionaryPanelState(
    val title: String,
    val lookupButtonLabel: String,
    val closeLabel: String,
    val hintMessage: String,
    val notFoundMessage: String,
    val loadingMessage: String,
    val errorMessage: String,
    val synonymsLabel: String,
    val isLoading: Boolean,
    val entries: List<DictionaryEntry>,
    val lookedUpWord: String,
    val hasFailed: Boolean,
    val availableDictionaries: List<ServiceInfo> = emptyList(),
    val selectedDictionaryId: String? = null,
    // Auto-source cycling
    val autoSource: DictionaryAutoSource = DictionaryAutoSource.TRANSLATED,
    val autoSourceOffLabel: String = "",
    val autoSourceTranslatedLabel: String = "",
    val autoSourceSourceLabel: String = "",
    val onAutoSourceChanged: (DictionaryAutoSource) -> Unit = {},
)
