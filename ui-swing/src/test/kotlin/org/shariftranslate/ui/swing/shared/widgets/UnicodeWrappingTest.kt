package org.shariftranslate.ui.swing.shared.widgets

import kotlin.test.Test
import kotlin.test.assertEquals

class UnicodeWrappingTest {
    @Test fun breaksPreserveSurrogatePairsAndCombiningMarks() {
        assertEquals(1, safeTextBreak("A😀B", 2))
        assertEquals(3, safeTextBreak("A😀B", 3))
        assertEquals(1, safeTextBreak("Ae\u0301B", 2))
        assertEquals(3, safeTextBreak("Ae\u0301B", 3))
        assertEquals(1, safeTextBreak("Aس\u064eB", 2))
        assertEquals(0, safeTextBreak("", 0))
        assertEquals(3, safeTextBreak("abc", 10))
    }
    @Test fun documentDirectionSurvivesOppositeInterfaceOrientation() {
        javax.swing.SwingUtilities.invokeAndWait {
            val pane = AdvancedTextPane({}, {}, {})
            pane.render("Hello world", emptyList(), true)
            pane.applyComponentOrientation(java.awt.ComponentOrientation.RIGHT_TO_LEFT)
            assertEquals(true, pane.componentOrientation.isLeftToRight)
            pane.render("سلام دنیا", emptyList(), true)
            pane.applyComponentOrientation(java.awt.ComponentOrientation.LEFT_TO_RIGHT)
            assertEquals(false, pane.componentOrientation.isLeftToRight)
        }
    }

}
