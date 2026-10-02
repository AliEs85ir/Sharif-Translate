package com.github.ahatem.qtranslate.ui.swing.shared.theme

import javax.swing.UIManager

/** Shape and spacing shared by every bundled theme, including themes selected at runtime. */
object ModernUiDefaults {
    fun install() {
        UIManager.put("Button.arc", 16)
        UIManager.put("Component.arc", 16)
        UIManager.put("TextComponent.arc", 14)
        UIManager.put("ProgressBar.arc", 12)
        UIManager.put("ScrollBar.thumbArc", 999)
        UIManager.put("ScrollBar.width", 10)
        UIManager.put("Component.focusWidth", 1)
    }
}
