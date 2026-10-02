package org.shariftranslate.ui.swing.main.selector

import org.shariftranslate.core.main.domain.model.ServiceInfo
import org.shariftranslate.core.shared.arch.UiState

data class TranslatorSelectorState(
    val availableTranslators: List<ServiceInfo>,
    val selectedTranslatorId: String?,
    val isLoading: Boolean
) : UiState