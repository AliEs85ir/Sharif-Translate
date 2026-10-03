package org.shariftranslate.ui.swing.main

import org.shariftranslate.ui.swing.shared.icon.SuppliedIcons
import com.formdev.flatlaf.FlatLightLaf
import com.formdev.flatlaf.FlatDarkLaf
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.test.*

class SuppliedIconsTest {
    @Test fun suppliedActionsRenderInBothThemesAndAtDifferentSizes() {
        SwingUtilities.invokeAndWait {
            val original = javax.swing.UIManager.getLookAndFeel()
            try {
                for (dark in listOf(false, true)) {
                    if (dark) FlatDarkLaf.setup() else FlatLightLaf.setup()
                    for (name in listOf("copy-text", "volume", "close", "pin", "star", "collection", "settings", "swap", "scan-text", "check")) {
                        for (size in listOf(13, 16, 24, 36)) {
                            val icon = assertNotNull(SuppliedIcons.find("icons/lucide/$name.svg", size, size))
                            val image = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
                            image.createGraphics().also { icon.paintIcon(null, it, 0, 0); it.dispose() }
                            assertTrue((0 until image.width).any { x -> (0 until image.height).any { y -> image.getRGB(x, y) ushr 24 != 0 } })
                        }
                    }
                }
            } finally { javax.swing.UIManager.setLookAndFeel(original) }
        }
    }
}
