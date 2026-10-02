package org.shariftranslate.ui.swing.shared.fonts

import com.formdev.flatlaf.util.FontUtils

object VazirmatnFont {
    const val FAMILY = "Vazirmatn"

    fun install() {
        listOf("Vazirmatn-Regular.ttf", "Vazirmatn-Bold.ttf").forEach { name ->
            VazirmatnFont::class.java.getResource("/fonts/sans/vazirmatn/$name")
                ?.let(FontUtils::installFont)
        }
    }
}
