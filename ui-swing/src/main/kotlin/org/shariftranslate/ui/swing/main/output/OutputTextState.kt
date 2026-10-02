package org.shariftranslate.ui.swing.main.output

import org.shariftranslate.api.rewriter.RewriteStyle
import org.shariftranslate.api.summarizer.SummaryLength
import org.shariftranslate.core.settings.data.ExtraOutputType
import org.shariftranslate.core.settings.data.FontConfig
import org.shariftranslate.core.shared.arch.UiState
import org.shariftranslate.ui.swing.main.widgets.TextActionsState

data class OutputTextState(
    val text: String,
    val fontConfig: FontConfig,
    val fallbackFontConfig: FontConfig,
    val isLoading: Boolean,
    val actionsState: TextActionsState,
    val isEditable: Boolean = false
) : UiState

data class ExtraOutputState(
    val text: String,
    val fontConfig: FontConfig,
    val fallbackFontConfig: FontConfig,
    val isLoading: Boolean,
    val isVisible: Boolean,
    val actionsState: TextActionsState,
    val isEditable: Boolean = false,

    val activeType: ExtraOutputType = ExtraOutputType.None,
    val summaryLength: SummaryLength = SummaryLength.MEDIUM,
    val rewriteStyle: RewriteStyle = RewriteStyle.FORMAL,

    val labelBackward: String = "",
    val labelSummary: String = "",
    val labelRewrite: String = "",

    val labelConfigure: String = "",
    val summaryLengthLabels: List<String> = emptyList(),
    val rewriteStyleLabels: List<String> = emptyList(),

    val onTypeChanged: (ExtraOutputType) -> Unit = {},
    val onSummaryLengthChanged: (SummaryLength) -> Unit = {},
    val onRewriteStyleChanged: (RewriteStyle) -> Unit = {}
) : UiState