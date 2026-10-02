package com.github.ahatem.qtranslate.ui.swing.quciktranslate

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorPopupButton
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorSelectorState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.*
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.ComponentMover
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.ComponentResizer
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.*
import java.awt.event.*
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.math.abs
import kotlin.math.max


class QuickTranslateDialog(
    private val owner: Frame,
    private val iconManager: IconManager,
    private val onDismiss: () -> Unit,
    onTranslatorSelected: (String) -> Unit,
    private val onListen: () -> Unit,
    private val onCopy: () -> Unit,
    private val onSourceLanguageSelected: (LanguageCode) -> Unit,
    private val onTargetLanguageSelected: (LanguageCode) -> Unit,
    private val onSwapLanguages: () -> Unit,
    private val onSavePosition: (Position) -> Unit,
    private val onSaveSize: (Size) -> Unit,
    private val onPinToggled: () -> Unit
) : JDialog(owner, ModalityType.MODELESS), Renderable<QuickTranslateDialogState> {

    private companion object {
        const val MAX_WIDTH_SCALE = 0.52
        const val MAX_HEIGHT_SCALE = 0.55
        const val RESIZE_HANDLE_SIZE = 8
        const val COPY_FEEDBACK_DURATION_MS = 1000
        const val FADE_MS = 160
        const val FADE_STEPS = 8
        const val RESIZE_SAVE_DEBOUNCE_MS = 180
    }

    // theme colors cached
    private val borderColor = UIManager.getColor("Component.borderColor")
    private val toolbarSelectedBg = UIManager.getColor("Button.toolbar.selectedBackground")
    private val toolbarSelectedFg = UIManager.getColor("Button.toolbar.selectedForeground")
    private val labelFg = UIManager.getColor("Label.foreground")

    // title + controls
    private var currentState: QuickTranslateDialogState? = null
    private var detailsExpanded = false
    private val sourceButton = popupButton()
    private val targetButton = popupButton()
    private val swapButton = popupButton("icons/lucide/swap.svg", "icons/lucide/swap.svg")
    private val translatorComboBox = TranslatorPopupButton(iconManager, onTranslatorSelected)

    private val pinButton = popupButton("icons/custom/pin.png", "icons/lucide/pin.svg")
    private val listenButton = popupButton("icons/custom/speaker.png", "icons/lucide/volume.svg")
    private val copyButton = popupButton("icons/custom/copy.png", "icons/lucide/copy-text.svg")
    private val normalCopyIcon = copyButton.icon
    private val closeButton = popupButton("icons/custom/close.png", "icons/lucide/close.svg")
    private val favoriteButton = popupButton("icons/custom/star.png", "icons/lucide/check.svg")
    private val collectionButton = popupButton("icons/custom/collection.png", "icons/lucide/book-open.svg")
    private val moreButton = popupButton()
    private val originalLabel = JLabel()
    private val originalText = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = EmptyBorder(2, 0, 0, 0)
    }
    private val detailsPanel = JPanel(BorderLayout(0, 4)).apply {
        isOpaque = false
        border = EmptyBorder(12, 18, 10, 18)
        add(originalLabel, BorderLayout.NORTH)
        add(JScrollPane(originalText).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = null
            preferredSize = Dimension(0, 58)
        }, BorderLayout.CENTER)
        isVisible = false
    }

    // content
    private val outputTextArea = AdvancedTextPane(
        onTextChanged = {},
        onTranslateRequest = {},
        onListenRequest = { onListen() }
    ).apply {
        isEditable = false
        border = EmptyBorder(18, 20, 18, 20)
    }

    private val topPanel = createTopPanel()
    private val bottomPanel = createBottomPanel()

    // sizing/measuring
    private val measurePane: JTextPane by lazy {
        JTextPane().apply {
            editorKit = outputTextArea.editorKit
            isEditable = false
            putClientProperty("JEditorPane.honorDisplayProperties", true)
        }
    }

    // timers and state
    private var fadeTimer: Timer? = null
    private var copyFeedbackTimer: Timer? = null
    private var resizeSaveTimer: Timer? = null
    private var expandTimer: Timer? = null

    // idle/auto-hide manager (single timer)
    private var idleHideTimer: Timer? = null

    // flags
    private var isDragging = false
    private var isResizing = false
    private var isPinned = false
    private var wasManuallyMoved = false
    private var currentConfig: DialogConfig? = null

    private var lastRenderedText: String? = null

    // mouse presence detection via AWT
    private var awtMouseListener: AWTEventListener? = null
    private var isMouseOver = false
    private var isLanguageMenuOpen = false

    init {
        isUndecorated = true
        isAlwaysOnTop = true
        minimumSize = Dimension(510, 230)
        focusableWindowState = false
        background = Color(0, 0, 0, 0)
        rootPane.isOpaque = false
        layeredPane.isOpaque = false

        val wrapperPanel = object : JPanel(BorderLayout()) {
            override fun paintComponent(graphics: Graphics) {
                val g = graphics.create() as Graphics2D
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                for (spread in 10 downTo 2 step 2) {
                    g.color = Color(10, 25, 55, 4)
                    g.fillRoundRect(spread, spread, width - spread * 2, height - spread * 2, 30, 30)
                }
                g.dispose()
            }
        }.apply {
            border = EmptyBorder(12, 12, 12, 12)
            isOpaque = false
        }
        contentPane = wrapperPanel

        val mainPanel = object : JPanel(BorderLayout()) {
            override fun paintComponent(graphics: Graphics) {
                val g = graphics.create() as Graphics2D
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = UIManager.getColor("Panel.background") ?: Color.WHITE
                g.fillRoundRect(1, 1, width - 2, height - 2, 22, 22)
                g.color = UIManager.getColor("Component.borderColor") ?: Color.LIGHT_GRAY
                g.drawRoundRect(1, 1, width - 3, height - 3, 22, 22)
                g.dispose()
            }
        }.apply {
            isOpaque = false
            border = EmptyBorder(5, 5, 5, 5)
        }
        wrapperPanel.add(mainPanel, BorderLayout.CENTER)

        val textScrollPane = JScrollPane(outputTextArea).apply {
            putClientProperty(
                FlatClientProperties.STYLE,
                "borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; innerOutlineWidth: 0;"
            )
            border = null
            viewport.border = null

            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        mainPanel.add(topPanel, BorderLayout.NORTH)
        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(textScrollPane, BorderLayout.CENTER)
            add(detailsPanel, BorderLayout.SOUTH)
        }
        mainPanel.add(contentPanel, BorderLayout.CENTER)
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = updateWindowShape()
        })

        setupWindowBehavior(topPanel)
        updatePinButtonStyle(isPinned)
    }

    // Render entrypoint
    override fun render(state: QuickTranslateDialogState) {
        val wasVisible = isVisible
        val visibilityChanged = wasVisible != state.isVisible

        if (visibilityChanged) {
            if (state.isVisible) {
                currentConfig = state.config
                wasManuallyMoved = false
                detailsExpanded = false
                detailsPanel.isVisible = false

                // ensure correct font before rendering or measuring text
                outputTextArea.updateFontsAndRescanDocument(
                    newPrimary = state.config.font.toFont(),
                    newFallback = state.config.fallbackFont.toFont()
                )

                updateContent(state)
                updatePinButtonStyle(state.isPinned)
                applySize(state.translatedText)
                applyPosition()
                showDialog()
            } else {
                hideDialog()
            }
            return
        }

        if (!isVisible) return

        val pinStateChanged = this.isPinned != state.isPinned

        updateContent(state)

        if (pinStateChanged) handlePinState(state)

        // only refresh font when user changed it
        if (!isResizing && !isDragging) {
            outputTextArea.updateFontsAndRescanDocument(
                newPrimary = state.config.font.toFont(),
                newFallback = state.config.fallbackFont.toFont()
            )
        }
    }

    // Full content sync
    private fun updateContent(state: QuickTranslateDialogState) {
        this.isPinned = state.isPinned
        currentState = state

        sourceButton.text = shortLanguageName(state.sourceLanguage, state.strings.autoDetect) + "  ⌄"
        targetButton.text = shortLanguageName(state.targetLanguage, state.strings.autoDetect) + "  ⌄"
        sourceButton.toolTipText = state.sourceLanguage.getDisplayName(autoDetectLabel = state.strings.autoDetect)
        targetButton.toolTipText = state.targetLanguage.getDisplayName(autoDetectLabel = state.strings.autoDetect)
        sourceButton.isEnabled = !state.isLoading && state.availableLanguages.isNotEmpty()
        targetButton.isEnabled = sourceButton.isEnabled
        swapButton.isEnabled = !state.isLoading && state.sourceLanguage != LanguageCode.AUTO
        swapButton.toolTipText = state.strings.swap

        translatorComboBox.render(
            TranslatorSelectorState(
                availableTranslators = state.translatorSelectorState.availableTranslators,
                selectedTranslatorId = state.translatorSelectorState.selectedTranslatorId,
                isLoading = state.isLoading
            )
        )

        pinButton.toolTipText = if (state.isPinned) state.strings.unpinTooltip else state.strings.pinTooltip
        listenButton.toolTipText = state.strings.listenTooltip
        copyButton.toolTipText = state.strings.copyTooltip
        closeButton.toolTipText = UIManager.getString("InternalFrameTitlePane.closeButtonAccessibleName") ?: "Close"
        favoriteButton.toolTipText = "${state.strings.favorite} · ${state.strings.comingSoon}"
        collectionButton.toolTipText = "${state.strings.collection} · ${state.strings.comingSoon}"
        favoriteButton.isEnabled = false
        collectionButton.isEnabled = false
        moreButton.text = if (detailsExpanded) state.strings.less else state.strings.more
        originalLabel.text = state.strings.original
        if (originalText.text != state.sourceText) {
            originalText.text = state.sourceText
            originalText.caretPosition = 0
        }

        listenButton.isEnabled = state.actionsState.canListen && !state.isLoading
        copyButton.isEnabled = state.actionsState.canCopy && !state.isLoading

        val textToRender = if (state.isLoading) state.strings.loadingText else state.translatedText
        if (lastRenderedText != textToRender) {
            lastRenderedText = textToRender
            outputTextArea.render(textToRender, emptyList(), false)
        }
    }

    private fun handlePinState(state: QuickTranslateDialogState) {
        this.isPinned = state.isPinned
        updatePinButtonStyle(state.isPinned)

        if (state.isPinned) {
            stopIdleHide()
            fadeTo(1f, FADE_MS)
        } else {
            applyTransparency()
            startIdleHide()
        }
    }

    private fun updatePinButtonStyle(pinned: Boolean) {
        pinButton.putClientProperty("JButton.selected", pinned)

        if (pinned) {
            pinButton.background = toolbarSelectedBg
            pinButton.foreground = toolbarSelectedFg ?: labelFg
        } else {
            pinButton.background = null
            pinButton.foreground = labelFg
        }
        pinButton.repaint()
    }

    private fun applyTransparency() {
        val transparency = currentConfig?.transparencyPercentage ?: 0
        val target = (100f - transparency) / 100f
        fadeTo(target, FADE_MS)
    }

    private fun fadeTo(targetOpacity: Float, durationMs: Int) {
        fadeTimer?.stop()

        val start = opacity
        val steps = max(1, FADE_STEPS)
        val stepDelay = max(10, durationMs / steps)
        var step = 0

        fadeTimer = Timer(stepDelay) {
            step++
            val t = step.toFloat() / steps
            val value = start + (targetOpacity - start) * t
            setOpacityIfDifferent(value)

            if (step >= steps) {
                (it.source as Timer).stop()
            }
        }.apply {
            isRepeats = true
            start()
        }
    }

    private fun setOpacityIfDifferent(value: Float) {
        if (abs(opacity - value) > 0.01f) opacity = value
    }

    private fun showDialog() {
        // apply initial opacity from config (without animation)
        val transparency = currentConfig?.transparencyPercentage ?: 0
        opacity = (100f - transparency) / 100f

        updateWindowShape()
        isVisible = true
        focusableWindowState = true
        installAwtMouseListener()
        if (!isPinned) startIdleHide()
    }

    private fun hideDialog() {
        if (!isVisible) return
        fadeTimer?.stop()
        expandTimer?.stop()
        stopIdleHide()
        uninstallAwtMouseListener()
        isVisible = false
        focusableWindowState = false
        onSavePosition(location.toPosition())
        onSaveSize(size.toSize())
    }

    private fun updateWindowShape() {
        if (width <= 0 || height <= 0) return
        runCatching {
            shape = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 20f, 20f)
        }
    }

    private fun startIdleHide() {
        // restart single idle timer — reads live config each call so changes take effect immediately
        val idleHideDelayMs = (currentConfig?.idleTimeoutSeconds ?: 3) * 1000
        idleHideTimer?.stop()
        idleHideTimer = Timer(idleHideDelayMs) { event ->
            if (!isPinned) fadeTo(0f, FADE_MS) // fade out visually
            // after fade complete, actually hide
            Timer(FADE_MS + 20) {
                if (!isPinned && !isMouseOver && !isLanguageMenuOpen) {
                    onDismiss()
                }
                (it.source as Timer).stop()
            }.apply { isRepeats = false; start() }
            (event.source as Timer).stop()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun stopIdleHide() {
        idleHideTimer?.stop()
    }

    private fun installAwtMouseListener() {
        if (awtMouseListener != null) return
        awtMouseListener = AWTEventListener { ev ->
            val me = ev as? MouseEvent ?: return@AWTEventListener
            if (me.id != MouseEvent.MOUSE_MOVED && me.id != MouseEvent.MOUSE_ENTERED && me.id != MouseEvent.MOUSE_EXITED) return@AWTEventListener
            SwingUtilities.invokeLater {
                val p = MouseInfo.getPointerInfo()?.location ?: return@invokeLater
                val cp = Point(p)
                SwingUtilities.convertPointFromScreen(cp, contentPane)
                val over = contentPane.contains(cp)
                if (over != isMouseOver) {
                    isMouseOver = over
                    if (isMouseOver || isLanguageMenuOpen) {
                        stopIdleHide()
                        fadeTo(1f, FADE_MS)
                    } else {
                        if (!isPinned) {
                            applyTransparency()
                            startIdleHide()
                        }
                    }
                } else {
                    // mouse moved inside window: reset idle timer
                    if (isMouseOver && !isPinned && !isLanguageMenuOpen) startIdleHide()
                }
            }
        }
        Toolkit.getDefaultToolkit()
            .addAWTEventListener(awtMouseListener, AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK)
    }

    private fun uninstallAwtMouseListener() {
        awtMouseListener?.let {
            Toolkit.getDefaultToolkit().removeAWTEventListener(it)
            awtMouseListener = null
            isMouseOver = false
        }
    }

    // sizing (reuse measurePane; skip heavy ops during resize)
    private fun applySize(text: String) {
        val config = currentConfig ?: return
        if (!config.autoSizeEnabled) {
            size = config.lastKnownSize.toDimension()
            return
        }

        if (isResizing) {
            // defer measurement until resize end
            return
        }

        // Use the monitor where the mouse cursor currently lives, not where the dialog
        // happens to be placed — prevents wrong-monitor bounds on multi-monitor setups.
        val gc = MouseInfo.getPointerInfo()?.device?.defaultConfiguration ?: graphicsConfiguration
        val screenBounds = gc.bounds
        val maxWidth = (screenBounds.width * MAX_WIDTH_SCALE).toInt()
        val maxHeight = (screenBounds.height * MAX_HEIGHT_SCALE).toInt()

        measurePane.font = outputTextArea.font
        if (measurePane.text != text) measurePane.text = text
        measurePane.size = Dimension(maxWidth, Int.MAX_VALUE)

        val textWidth = measurePane.preferredSize.width + 40
        val textHeight = measurePane.preferredSize.height + 30

        val borderSize = RESIZE_HANDLE_SIZE * 2
        val finalWidth = (textWidth + borderSize)
            .coerceAtMost(maxWidth)
            .coerceAtLeast(minimumSize.width)

        val nonTextHeight = topPanel.preferredSize.height + bottomPanel.preferredSize.height + 20
        val finalHeight = (textHeight + nonTextHeight + borderSize)
            .coerceAtMost(maxHeight)
            .coerceAtLeast(minimumSize.height)

        if (width != finalWidth || height != finalHeight) {
            size = Dimension(finalWidth, finalHeight)
            if (isVisible) revalidate()
        }
    }

    private fun applyPosition() {
        val config = currentConfig ?: return
        if (wasManuallyMoved) return

        if (config.autoPositionEnabled) {
            val mouseLocation = MouseInfo.getPointerInfo()?.location ?: run {
                setLocationRelativeTo(owner)
                return
            }

            // Derive screen bounds from the monitor the mouse is on, not the dialog's current
            // monitor — ensures correct clamping on multi-monitor setups (A-10).
            val gc = MouseInfo.getPointerInfo()?.device?.defaultConfiguration ?: graphicsConfiguration
            val screenBounds = gc.bounds
            val dialogWidth = width
            val dialogHeight = height
            val offsetX = 10
            val offsetY = 10

            var x = mouseLocation.x + offsetX
            var y = mouseLocation.y + offsetY

            if (x + dialogWidth > screenBounds.x + screenBounds.width) {
                x = mouseLocation.x - dialogWidth - offsetX
            }

            if (y + dialogHeight > screenBounds.y + screenBounds.height) {
                y = mouseLocation.y - dialogHeight - offsetY
            }

            x = x.coerceAtLeast(screenBounds.x)
            y = y.coerceAtLeast(screenBounds.y)

            setLocation(x, y)
        } else {
            location = config.lastKnownPosition.toPoint()
        }
    }

    // Copy feedback: small animation reuse
    private fun showCopyFeedback() {
        copyFeedbackTimer?.stop()

        val checkIcon = iconManager.getIcon("icons/lucide/check.svg", 13, 13)
        copyButton.icon = (checkIcon as FlatSVGIcon).applyForegroundColorFilter()
        copyButton.foreground = UIManager.getColor("Button.successForeground") ?: Color(34, 197, 94)

        copyFeedbackTimer = Timer(COPY_FEEDBACK_DURATION_MS) {
            copyButton.icon = normalCopyIcon
            copyButton.foreground = null
            (it.source as Timer).stop()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun popupButton(iconPath: String? = null, darkFallback: String? = null): JButton {
        val base = UIManager.getColor("Panel.background") ?: Color.WHITE
        val hover = UIManager.getColor("Button.hoverBackground") ?: Color(230, 239, 255)
        return JButton().apply {
            isFocusable = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(if (iconPath == null) 110 else 38, 38)
            putClientProperty(FlatClientProperties.STYLE, "arc: 999; borderWidth: 1; focusWidth: 0")
            background = base
            if (iconPath != null) {
                val path = when {
                    FlatSVGIcon.isDarkLaf() && iconPath.startsWith("icons/custom/") ->
                        iconPath.replace("icons/custom/", "icons/custom/dark/")
                    FlatSVGIcon.isDarkLaf() && darkFallback != null -> darkFallback
                    else -> iconPath
                }
                icon = iconManager.getIcon(path, 19, 19).let {
                    if (it is FlatSVGIcon) it.applyForegroundColorFilter() else it
                }
            }
            addMouseListener(object : MouseAdapter() {
                private var animation: Timer? = null
                private fun animate(to: Color) {
                    animation?.stop()
                    val from = background ?: base
                    var step = 0
                    animation = Timer(18) { event ->
                        step++
                        val t = (step / 8f).coerceAtMost(1f)
                        background = Color(
                            (from.red + (to.red - from.red) * t).toInt(),
                            (from.green + (to.green - from.green) * t).toInt(),
                            (from.blue + (to.blue - from.blue) * t).toInt()
                        )
                        if (step >= 8) (event.source as Timer).stop()
                    }.apply { start() }
                }
                override fun mouseEntered(e: MouseEvent) { if (isEnabled) animate(hover) }
                override fun mouseExited(e: MouseEvent) { animate(base) }
            })
        }
    }

    private fun shortLanguageName(language: LanguageCode, autoDetect: String): String {
        val name = language.getDisplayName(autoDetectLabel = autoDetect)
        return if (name.length > 19) name.take(18) + "…" else name
    }

    private fun showLanguageMenu(anchor: JButton, source: Boolean) {
        val state = currentState ?: return
        val languages = state.availableLanguages.filter { source || it != LanguageCode.AUTO }
        if (languages.isEmpty()) return
        val popup = JPopupMenu().apply { border = EmptyBorder(8, 8, 8, 8) }
        isLanguageMenuOpen = true
        stopIdleHide()
        popup.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) {}
            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent) {
                isLanguageMenuOpen = false
                if (!isPinned) startIdleHide()
            }
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent) {
                isLanguageMenuOpen = false
                if (!isPinned) startIdleHide()
            }
        })
        val search = JTextField().apply {
            putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "⌕")
            preferredSize = Dimension(215, 32)
        }
        val model = DefaultListModel<LanguageCode>()
        val list = JList(model).apply {
            fixedCellHeight = 32
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    text = (value as? LanguageCode)?.getDisplayName(autoDetectLabel = state.strings.autoDetect) ?: ""
                    border = EmptyBorder(0, 9, 0, 9)
                    return this
                }
            }
        }
        fun filter(query: String) {
            model.clear()
            languages.filter {
                it.tag.contains(query, true) ||
                    it.getDisplayName(autoDetectLabel = state.strings.autoDetect).contains(query, true)
            }.forEach(model::addElement)
        }
        filter("")
        search.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = filter(search.text)
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = filter(search.text)
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = filter(search.text)
        })
        fun choose() {
            val language = list.selectedValue ?: return
            popup.isVisible = false
            if (source) onSourceLanguageSelected(language) else onTargetLanguageSelected(language)
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 1) choose() }
        })
        search.addActionListener { if (model.size() > 0) { list.selectedIndex = 0; choose() } }
        popup.layout = BorderLayout(0, 6)
        popup.add(search, BorderLayout.NORTH)
        popup.add(JScrollPane(list).apply {
            preferredSize = Dimension(230, 240)
            border = null
        }, BorderLayout.CENTER)
        popup.show(anchor, 0, anchor.height + 4)
        search.requestFocusInWindow()
    }

    private fun toggleDetails() {
        expandTimer?.stop()
        val expand = !detailsExpanded
        detailsExpanded = expand
        currentState?.let { moreButton.text = if (expand) it.strings.less else it.strings.more }
        if (expand) detailsPanel.isVisible = true
        val startHeight = height
        val gc = graphicsConfiguration ?: return
        val maxHeight = (gc.bounds.height * MAX_HEIGHT_SCALE).toInt()
        val targetHeight = (startHeight + if (expand) 90 else -90)
            .coerceIn(minimumSize.height, maxHeight)
        var step = 0
        expandTimer = Timer(16) { event ->
            step++
            val t = (step / 10f).coerceAtMost(1f)
            val eased = 1f - (1f - t) * (1f - t)
            val nextHeight = startHeight + ((targetHeight - startHeight) * eased).toInt()
            setSize(width, nextHeight)
            val bottom = gc.bounds.y + gc.bounds.height
            if (y + nextHeight > bottom) setLocation(x, bottom - nextHeight)
            if (step >= 10) {
                (event.source as Timer).stop()
                if (!expand) detailsPanel.isVisible = false
                revalidate()
            }
        }.apply { start() }
    }

    private fun createTopPanel(): JPanel {
        sourceButton.preferredSize = Dimension(166, 38)
        targetButton.preferredSize = Dimension(166, 38)
        swapButton.preferredSize = Dimension(38, 38)
        sourceButton.addActionListener { showLanguageMenu(sourceButton, true) }
        targetButton.addActionListener { showLanguageMenu(targetButton, false) }
        swapButton.addActionListener { onSwapLanguages() }
        closeButton.addActionListener { onDismiss() }
        val languages = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(sourceButton)
            add(swapButton)
            add(targetButton)
        }
        val controls = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(closeButton)
        }
        return JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor),
                EmptyBorder(12, 14, 12, 12)
            )
            add(languages, BorderLayout.CENTER)
            add(controls, BorderLayout.EAST)
        }
    }

    private fun createBottomPanel(): JPanel {
        moreButton.foreground = if (FlatSVGIcon.isDarkLaf()) Color(129, 178, 255) else Color(25, 103, 210)
        originalLabel.font = originalLabel.font.deriveFont(Font.BOLD)
        pinButton.addActionListener { onPinToggled() }
        listenButton.addActionListener { onListen() }
        copyButton.addActionListener { onCopy(); showCopyFeedback() }
        moreButton.addActionListener { toggleDetails() }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 7, 0)).apply {
            isOpaque = false
            add(listenButton)
            add(copyButton)
            add(favoriteButton)
            add(collectionButton)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 7, 0)).apply {
            isOpaque = false
            add(pinButton)
            add(translatorComboBox)
            add(moreButton)
        }
        return JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor),
                EmptyBorder(10, 14, 10, 12)
            )
            add(actions, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }
    }


    private fun setupWindowBehavior(topPanel: JPanel) {
        val dragInsets = Insets(RESIZE_HANDLE_SIZE, RESIZE_HANDLE_SIZE, RESIZE_HANDLE_SIZE, RESIZE_HANDLE_SIZE)

        ComponentMover.builder()
            .destinationComponent(this)
            .build()
            .register(topPanel)

        val resizer = ComponentResizer.builder()
            .dragInsets(dragInsets)
            .minimumSize(minimumSize)
            .onResizeStart {
                // freeze opacity and suspend idle timer
                isResizing = true
                stopIdleHide()
                fadeTo(1f, FADE_MS)
            }
            .onResizeEnd {
                // restore opacity and save size once
                isResizing = false

                resizeSaveTimer?.stop()
                resizeSaveTimer = Timer(RESIZE_SAVE_DEBOUNCE_MS) {
                    onSaveSize(size.toSize())
                    // rescan fonts/doc after resize
                    currentConfig?.let { cfg ->
                        outputTextArea.updateFontsAndRescanDocument(
                            newPrimary = cfg.font.toFont(),
                            newFallback = cfg.fallbackFont.toFont()
                        )
                    }
                    (it.source as Timer).stop()
                }.apply { isRepeats = false; start() }

                if (!isPinned) {
                    applyTransparency()
                    startIdleHide()
                }
            }
            .build()

        resizer.register(this)

        val dragListener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                isDragging = true
                wasManuallyMoved = true // mark manual move immediately
                stopIdleHide()
                autoHideStopForDrag()
            }

            override fun mouseReleased(e: MouseEvent) {
                isDragging = false
                onSavePosition(location.toPosition())
                if (!isPinned) startIdleHide()
            }
        }

        topPanel.addMouseListener(dragListener)
        topPanel.addMouseMotionListener(dragListener)

        // Also track manual window drag if user drags from edges (to catch non-top-panel moves)
        addMouseListener(dragListener)
        addMouseMotionListener(dragListener)

        addWindowFocusListener(object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) {
                // reset idle when gaining focus
                if (!isPinned) startIdleHide()
            }

            override fun windowLostFocus(e: WindowEvent?) {
                // don't hide immediately on focus loss; start idle hide instead
                if (!isPinned) startIdleHide()
            }
        })

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = onDismiss()
            override fun windowClosed(e: WindowEvent) {
                uninstallAwtMouseListener()
            }
        })

        rootPane.registerKeyboardAction(
            {
                if (!isPinned) onDismiss()
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )
    }

    private fun autoHideStopForDrag() {
        // used to prevent premature hiding while user drags
        stopIdleHide()
        fadeTo(1f, FADE_MS / 2)
    }
}
