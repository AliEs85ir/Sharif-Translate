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
}
