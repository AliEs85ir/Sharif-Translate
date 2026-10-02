package com.github.ahatem.qtranslate.ui.swing.main.widgets

import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.ui.swing.shared.util.toFont
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

data class ReadOnlyTextPanelState(
    val text: String,
    val isVisible: Boolean,
    val isLoading: Boolean,
    val fontConfig: FontConfig,
    val fallbackFontConfig: FontConfig,
    val actionsState: TextActionsState,
    val isEditable: Boolean = false
) : UiState

class ReadOnlyTextPanel(
    private val textPane: AdvancedTextPane,
    private val actionsPanel: TextActionsPanel
) : JPanel(BorderLayout()), Renderable<ReadOnlyTextPanelState> {

    init {
        background = UIManager.getColor("TextArea.background") ?: UIManager.getColor("Panel.background")
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") ?: Color.GRAY, 1, true),
            EmptyBorder(9, 10, 9, 9)
        )
        val scrollPane = JScrollPane(textPane).apply {
            isFocusable = false
            border = null
        }

        val actionsWrapper = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 4, 0, 0)
            isOpaque = false
            add(actionsPanel, BorderLayout.CENTER)
        }

        add(scrollPane, BorderLayout.CENTER)
        add(actionsWrapper, BorderLayout.LINE_END)
    }

    override fun render(state: ReadOnlyTextPanelState) {
        isVisible = state.isVisible
        if (!isVisible) return

        textPane.render(
            text = state.text,
            corrections = emptyList(),
            isEditable = state.isEditable
        )

        textPane.updateFontsAndRescanDocument(
            newPrimary = state.fontConfig.toFont(),
            newFallback = state.fallbackFontConfig.toFont()
        )

        actionsPanel.render(state.actionsState)
    }
}
