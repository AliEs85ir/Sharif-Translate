package org.shariftranslate.ui.swing.shared.icon

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.util.ScaledImageIcon
import java.awt.Component
import java.awt.Graphics
import javax.swing.Icon
import javax.swing.ImageIcon

/** Original supplied PNGs, with theme selection at paint time and FlatLaf HiDPI scaling. */
object SuppliedIcons {
    private val names = setOf("arrow-left", "arrow-right", "check", "close", "copy-text", "globe", "keyboard", "layout-dashboard", "notification", "package", "palette", "pen-line", "pin", "scan-text", "settings", "sliders-horizontal", "swap", "text-align-start", "trash", "volume", "zap", "book-open", "star", "collection")
    fun find(path: String, width: Int, height: Int): Icon? {
        val basename = path.substringAfterLast('/').substringBeforeLast('.')
        val name = when (basename) { "speaker" -> "volume"; "copy" -> "copy-text"; else -> basename }
        if (name !in names || !(path.startsWith("icons/lucide/") || path.startsWith("icons/custom/"))) return null
        fun load(theme: String): ScaledImageIcon {
            val resource = requireNotNull(javaClass.classLoader.getResource("icons/supplied/$theme/$name.png"))
            return ScaledImageIcon(ImageIcon(resource), width, height)
        }
        val light = load("light")
        val dark = load("dark")
        return object : Icon {
            private fun current() = if (FlatLaf.isLafDark()) dark else light
            override fun getIconWidth() = current().iconWidth
            override fun getIconHeight() = current().iconHeight
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) = current().paintIcon(c, g, x, y)
        }
    }
}
