package org.shariftranslate.ui.swing.main

import org.shariftranslate.ui.swing.main.layout.*
import org.shariftranslate.ui.swing.shared.util.fittedWindowBounds
import java.awt.Rectangle
import javax.swing.*
import kotlin.test.*

class LayoutRegressionTest {
    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    @Test fun compactExtraTabVisibilityAndRepeatedUpdates() {
        val extra = JPanel()
        val tabs = JTabbedPane()
        val refs = LayoutComponentRefs.WithTabs(tabs)
        edt { extra.isVisible = false; refs.updateExtraOutputVisibility(true, extra) }
        edt { assertTrue(extra.isVisible); assertEquals(1, tabs.tabCount); refs.updateExtraOutputVisibility(true, extra) }
        edt { assertEquals(1, tabs.tabCount); refs.updateExtraOutputVisibility(false, extra) }
        edt { assertEquals(0, tabs.tabCount); assertFalse(extra.isVisible) }
    }
    @Test fun repeatedStateUpdatesPreserveUserDividerPosition() {
        val extra = JPanel()
        val main = JSplitPane()
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, main, extra)
        val refs = LayoutComponentRefs.WithSplitPanes(main, split)
        edt { split.setSize(600, 600); split.dividerSize = 0; refs.updateExtraOutputVisibility(true, extra) }
        edt { split.dividerLocation = 337; refs.updateExtraOutputVisibility(true, extra) }
        edt { assertEquals(337, split.dividerLocation) }
    }
    @Test fun restoredWindowsFitSmallAndNegativeOriginDisplays() {
        for (screen in listOf(Rectangle(0, 0, 800, 560), Rectangle(-1280, 0, 1280, 720), Rectangle(0, 0, 640, 480))) {
            val fitted = fittedWindowBounds(Rectangle(2000, 2000, 1020, 700), screen)
            assertTrue(screen.contains(fitted))
        }
    }
}
