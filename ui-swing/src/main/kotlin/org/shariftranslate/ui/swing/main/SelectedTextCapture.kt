package org.shariftranslate.ui.swing.main

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

/** Captures fresh selection without treating the old clipboard as selected text. */
internal class SelectedTextCapture(
    private val clipboard: Clipboard,
    private val copy: () -> Unit,
    private val waitForModifiers: suspend () -> Boolean = { true },
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun capture(): String? {
        if (!waitForModifiers()) return null
        // If the clipboard is busy, leave it alone and abort this invocation.
        val original = try { clipboard.getContents(null) } catch (_: IllegalStateException) { return null }
        val marker = StringSelection("")
        var ownedText = ""
        var changedClipboard = false
        try {
            clipboard.setContents(marker, null)
            changedClipboard = true
            copy()
            // Poll rather than always copying twice and sleeping a fixed amount.
            val deadline = System.nanoTime() + 750_000_000L
            repeat(75) {
                if (System.nanoTime() >= deadline) return ""
                pause(10)
                val text = try { readText() } catch (_: IllegalStateException) { return@repeat }
                if (text != null) ownedText = text
                if (!text.isNullOrBlank()) {
                    return text
                }
            }
            return ""
        } finally {
            if (changedClipboard) withContext(NonCancellable) {
                // Restore before dispatching an action, including cancellation/copy failures.
                // Preserve a newer clipboard write made by the user or another application.
                repeat(4) {
                    try {
                        if (readText() == ownedText) clipboard.setContents(original ?: marker, null)
                        return@withContext
                    } catch (_: IllegalStateException) { pause(10) }
                }
            }
        }
    }

    private fun readText(): String? {
        val contents: Transferable? = clipboard.getContents(null)
        return if (contents?.isDataFlavorSupported(DataFlavor.stringFlavor) == true)
            contents.getTransferData(DataFlavor.stringFlavor) as? String
        else null
    }
}
