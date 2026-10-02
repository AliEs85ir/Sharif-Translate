package org.shariftranslate.ui.swing.main

import kotlinx.coroutines.*
import org.shariftranslate.core.settings.data.HotkeyAction
import java.awt.datatransfer.*
import java.awt.event.KeyEvent
import java.awt.event.InputEvent
import kotlin.test.*

class ShortcutTest {
    @Test fun capturesIdenticalClipboardTextWithOneCopyAndPreservesWhitespace() = runBlocking {
        val clipboard = Clipboard("test")
        val original = StringSelection("  selected\nمتن 😀  ")
        clipboard.setContents(original, null)
        var copies = 0
        var polls = 0
        val capture = SelectedTextCapture(clipboard, {
            copies++
            clipboard.setContents(StringSelection("  selected\nمتن 😀  "), null)
        }, pause = { polls++ })
        assertEquals("  selected\nمتن 😀  ", capture.capture())
        assertEquals(1, copies)
        assertEquals(1, polls)
        assertSame(original, clipboard.getContents(null))
    }

    @Test fun noSelectionNeverReusesOldClipboard() = runBlocking {
        val clipboard = Clipboard("test")
        val original = StringSelection("old unrelated text")
        clipboard.setContents(original, null)
        var copies = 0
        assertEquals("", SelectedTextCapture(clipboard, { copies++ }, pause = {}).capture())
        assertEquals(1, copies)
        assertSame(original, clipboard.getContents(null))
    }

    @Test fun waitsForDelayedCopyWithoutSendingAnotherCopy() = runBlocking {
        val clipboard = Clipboard("test")
        var copies = 0
        var polls = 0
        val capture = SelectedTextCapture(clipboard, { copies++ }, pause = {
            if (++polls == 8) clipboard.setContents(StringSelection("fresh"), null)
        })
        assertEquals("fresh", capture.capture())
        assertEquals(8, polls)
        assertEquals(1, copies)
    }

    @Test fun slowSourceApplicationsHaveTimeToPublishSelection() = runBlocking {
        val clipboard = Clipboard("test")
        var polls = 0
        val capture = SelectedTextCapture(clipboard, {}, pause = {
            if (++polls == 45) clipboard.setContents(StringSelection("late selection"), null)
        })
        assertEquals("late selection", capture.capture())
    }

    @Test fun copyFailureRestoresOriginalClipboard() = runBlocking {
        val clipboard = Clipboard("test")
        val original = StringSelection("original")
        clipboard.setContents(original, null)
        assertFailsWith<IllegalArgumentException> {
            SelectedTextCapture(clipboard, { errorCopy() }, pause = {}).capture()
        }
        assertSame(original, clipboard.getContents(null))
    }

    @Test fun cancellationRestoresClipboard() = runBlocking {
        val clipboard = Clipboard("test")
        val original = StringSelection("original")
        clipboard.setContents(original, null)
        val copied = CompletableDeferred<Unit>()
        val job = launch {
            SelectedTextCapture(clipboard, { copied.complete(Unit) }).capture()
        }
        copied.await()
        job.cancelAndJoin()
        assertSame(original, clipboard.getContents(null))
    }

    @Test fun heldModifiersAbortWithoutTouchingClipboard() = runBlocking {
        val clipboard = Clipboard("test")
        val original = StringSelection("original")
        clipboard.setContents(original, null)
        assertNull(SelectedTextCapture(clipboard, { fail("Must not copy") }, { false }).capture())
        assertSame(original, clipboard.getContents(null))
    }

    @Test fun restoresNonTextClipboardFormats() = runBlocking {
        val clipboard = Clipboard("test")
        val original = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
            override fun getTransferData(flavor: DataFlavor) = listOf(java.io.File("selected.txt"))
        }
        clipboard.setContents(original, null)
        assertEquals("fresh", SelectedTextCapture(clipboard, {
            clipboard.setContents(StringSelection("fresh"), null)
        }, pause = {}).capture())
        assertSame(original, clipboard.getContents(null))
    }

    @Test fun temporaryClipboardContentionIsRetried() = runBlocking {
        var reads = 0
        val clipboard = object : Clipboard("test") {
            override fun getContents(requestor: Any?): Transferable? {
                if (++reads == 2) throw IllegalStateException("busy")
                return super.getContents(requestor)
            }
        }
        assertEquals("fresh", SelectedTextCapture(clipboard, {
            clipboard.setContents(StringSelection("fresh"), null)
        }, pause = {}).capture())
    }

    @Test fun busyClipboardAtStartAbortsWithoutCopying() = runBlocking {
        val clipboard = object : Clipboard("test") {
            override fun getContents(requestor: Any?): Transferable? = throw IllegalStateException("busy")
        }
        assertNull(SelectedTextCapture(clipboard, { fail("Must not copy") }).capture())
    }

    @Test fun ctrlChordsDoNotTriggerDoubleCtrl() {
        val taps = CtrlTapSequence()
        taps.pressed(true)
        taps.pressed(false) // Ctrl+C
        assertFalse(taps.released(true, 100))
        taps.pressed(true)
        taps.pressed(false) // Ctrl+Q
        assertFalse(taps.released(true, 200))
        taps.pressed(true)
        assertFalse(taps.released(true, 300))
        taps.pressed(true)
        assertTrue(taps.released(true, 400))
    }

    @Test fun ctrlTapSequenceRespectsTimeoutRepeatAndConsumesPairs() {
        val taps = CtrlTapSequence(400)
        taps.pressed(true)
        taps.pressed(true) // auto-repeat
        assertFalse(taps.released(true, 100))
        assertFalse(taps.released(true, 150)) // unmatched release
        taps.pressed(true)
        assertFalse(taps.released(true, 600)) // expired
        taps.pressed(true)
        assertTrue(taps.released(true, 700))
        taps.pressed(true)
        assertFalse(taps.released(true, 800)) // third tap does not retrigger
        taps.reset()
        taps.pressed(true)
        assertFalse(taps.released(true, 900))
    }

    @Test fun localActionsAndGlobalFocusDispatchRemainAvailable() {
        val scope = CoroutineScope(SupervisorJob())
        val focused = mutableListOf<HotkeyAction>()
        var translations = 0
        val listener = MainGlobalKeyListener(scope, {}, {}, {}, {}, {}, {},
            onTranslate = { translations++ }, onFocusPanel = { focused.add(it) })
        try {
            listener.setHotkeysEnabled(false) // This preference must survive initialization.
            assertFalse(listener.areHotkeysEnabled())
            listener.dispatchAction(HotkeyAction.TRANSLATE)
            listener.dispatchAction(HotkeyAction.FOCUS_OUTPUT)
            assertEquals(1, translations)
            assertEquals(listOf(HotkeyAction.FOCUS_OUTPUT), focused)
        } finally { scope.cancel() }
    }

    private fun errorCopy(): Nothing = throw IllegalArgumentException("copy failed")

    @Test fun windowsBindingsCoverPunctuationExtendedFunctionKeysAndAllModifiers() {
        assertEquals(0x0D, windowsVirtualKey(KeyEvent.VK_ENTER))
        assertEquals(0x2E, windowsVirtualKey(KeyEvent.VK_DELETE))
        assertEquals(0xBB, windowsVirtualKey(KeyEvent.VK_EQUALS))
        assertEquals(0xDC, windowsVirtualKey(KeyEvent.VK_BACK_SLASH))
        assertEquals(0xDE, windowsVirtualKey(KeyEvent.VK_QUOTE))
        assertEquals(0x7C, windowsVirtualKey(KeyEvent.VK_F13))
        assertEquals(0x87, windowsVirtualKey(KeyEvent.VK_F24))
        assertEquals(KeyEvent.VK_Q, windowsVirtualKey(KeyEvent.VK_Q))
        assertEquals(0x400F, windowsModifiers(InputEvent.ALT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or
            InputEvent.SHIFT_DOWN_MASK or InputEvent.META_DOWN_MASK))
        assertFailsWith<IllegalArgumentException> { windowsVirtualKey(KeyEvent.VK_UNDEFINED) }
    }
}
