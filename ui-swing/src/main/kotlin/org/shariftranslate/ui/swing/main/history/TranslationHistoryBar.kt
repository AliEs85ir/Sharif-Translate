package org.shariftranslate.ui.swing.main.history

import org.shariftranslate.ui.swing.shared.icon.IconManager
import org.shariftranslate.ui.swing.shared.util.createButtonWithIcon
import org.shariftranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.ComponentOrientation
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

class TranslationHistoryBar(
    private val iconManager: IconManager,
    private val onBackward: () -> Unit,
    private val onForward: () -> Unit,
    private val onImageTranslate: () -> Unit,
) : JPanel(BorderLayout()), Renderable<TranslationHistoryBarState> {

    private val backwardButton = createButtonWithIcon(iconManager, "icons/lucide/arrow-left.svg", 16)
    private val forwardButton = createButtonWithIcon(iconManager, "icons/lucide/arrow-right.svg", 16)
    private val imageTranslateButton = createButtonWithIcon(iconManager, "icons/lucide/scan-text.svg", 16)

    private val statusLabel = JLabel().apply {
        border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
        font = font.deriveFont(Font.BOLD)
    }

    private val leftGroup = JPanel(FlowLayout(FlowLayout.LEADING, 2, 0)).apply {
        isOpaque = false
        add(backwardButton)
        add(forwardButton)
    }

    private val rightGroup = JPanel(FlowLayout(FlowLayout.TRAILING, 2, 0)).apply {
        isOpaque = false
        add(imageTranslateButton)
    }

    init {
        border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
        backwardButton.addActionListener { onBackward() }
        forwardButton.addActionListener { onForward() }
        imageTranslateButton.addActionListener { onImageTranslate() }

        add(leftGroup, BorderLayout.LINE_START)
        add(statusLabel, BorderLayout.CENTER)
        add(rightGroup, BorderLayout.LINE_END)
    }

    override fun applyComponentOrientation(orientation: ComponentOrientation) {
        super.applyComponentOrientation(orientation)
        leftGroup.applyComponentOrientation(orientation)
        rightGroup.applyComponentOrientation(orientation)
        leftGroup.revalidate()
        rightGroup.revalidate()

        val isRtl = orientation == java.awt.ComponentOrientation.RIGHT_TO_LEFT
        backwardButton.icon = iconManager.getIcon(
            if (isRtl) "icons/lucide/arrow-right.svg" else "icons/lucide/arrow-left.svg",
            16, 16
        )
        forwardButton.icon = iconManager.getIcon(
            if (isRtl) "icons/lucide/arrow-left.svg" else "icons/lucide/arrow-right.svg",
            16, 16
        )
    }

    override fun render(state: TranslationHistoryBarState) {
        statusLabel.text = state.statusText
        statusLabel.toolTipText = state.statusText

        backwardButton.isEnabled = !state.isLoading && state.canGoBackward
        forwardButton.isEnabled = !state.isLoading && state.canGoForward
        imageTranslateButton.isEnabled = !state.isLoading

        backwardButton.toolTipText = state.strings.backwardTooltip
        forwardButton.toolTipText = state.strings.forwardTooltip
        imageTranslateButton.toolTipText = state.strings.imageTranslateTooltip
    }
}
