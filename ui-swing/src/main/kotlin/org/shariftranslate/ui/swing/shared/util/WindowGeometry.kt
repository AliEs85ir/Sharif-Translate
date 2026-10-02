package org.shariftranslate.ui.swing.shared.util

import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.Dimension

/** Keeps restored windows accessible after monitor or scaling changes. */
fun fittedWindowBounds(requested: Rectangle, usable: Rectangle): Rectangle {
    val width = requested.width.coerceIn(1, usable.width.coerceAtLeast(1))
    val height = requested.height.coerceIn(1, usable.height.coerceAtLeast(1))
    return Rectangle(requested.x.coerceIn(usable.x, usable.x + usable.width - width),
        requested.y.coerceIn(usable.y, usable.y + usable.height - height), width, height)
}

fun Window.fitToScreen() {
    val gc = graphicsConfiguration ?: return
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    val usable = Rectangle(gc.bounds).apply {
        x += insets.left; y += insets.top
        width -= insets.left + insets.right; height -= insets.top + insets.bottom
    }
    minimumSize = Dimension(minimumSize.width.coerceAtMost(usable.width), minimumSize.height.coerceAtMost(usable.height))
    bounds = fittedWindowBounds(bounds, usable)
}
