package com.github.ahatem.qtranslate.ui.swing.shared.fonts

import com.formdev.flatlaf.util.FontUtils

object InterFont {
    fun install() {
        listOf("Inter-Regular.ttf", "Inter-Medium.ttf", "Inter-SemiBold.ttf").forEach { name ->
            InterFont::class.java.getResource("/fonts/sans/inter/$name")
                ?.let(FontUtils::installFont)
        }
    }
}
