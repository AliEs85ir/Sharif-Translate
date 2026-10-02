package org.shariftranslate.ui.swing.main

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser.MSG
import com.tulskiy.keymaster.common.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.*
import javax.swing.KeyStroke

/** Blocking Win32 message pump: no polling delay, and registration finishes before returning. */
internal class WindowsHotkeyProvider : Provider() {
    private val commands = ConcurrentLinkedQueue<FutureTask<Unit>>()
    private val ready = CompletableFuture<Int>()
    private val hotkeys = mutableMapOf<Int, HotKey>() // accessed only by the native thread
    @Volatile private var closed = false
    private val thread = Thread({ messageLoop() }, "Windows hotkeys").apply { isDaemon = true }

    init {
        setUseSwingEventQueue(false)
        thread.start()
        try { ready.get(5, TimeUnit.SECONDS) }
        catch (e: Exception) { closed = true; super.stop(); throw e }
    }

    override fun init() = Unit

    override fun register(stroke: KeyStroke, listener: HotKeyListener) {
        val code = windowsVirtualKey(stroke.keyCode)
        execute { registerOnThread(HotKey(stroke, listener), code, windowsModifiers(stroke.modifiers)) }
    }

    override fun register(key: MediaKey, listener: HotKeyListener) {
        val code = when (key) {
            MediaKey.MEDIA_NEXT_TRACK -> 0xB0
            MediaKey.MEDIA_PREV_TRACK -> 0xB1
            MediaKey.MEDIA_STOP -> 0xB2
            MediaKey.MEDIA_PLAY_PAUSE -> 0xB3
        }
        execute { registerOnThread(HotKey(key, listener), code, 0x4000) }
    }

    private fun registerOnThread(hotkey: HotKey, code: Int, modifiers: Int) {
        unregisterOnThread { it.hasSameTrigger(hotkey) }
        val id = (1..0xBFFF).first { it !in hotkeys }
        check(User32.INSTANCE.RegisterHotKey(null, id, modifiers, code)) {
            "Windows refused hotkey $hotkey (error ${Kernel32.INSTANCE.GetLastError()})"
        }
        hotkeys[id] = hotkey
    }

    override fun unregister(stroke: KeyStroke) = execute { unregisterOnThread { it.keyStroke == stroke } }
    override fun unregister(key: MediaKey) = execute { unregisterOnThread { it.mediaKey == key } }
    override fun reset() = execute { unregisterOnThread { true } }

    private fun unregisterOnThread(matches: (HotKey) -> Boolean) {
        hotkeys.filterValues(matches).keys.toList().forEach { id ->
            User32.INSTANCE.UnregisterHotKey(null, id)
            hotkeys.remove(id)
        }
    }

    @Synchronized override fun stop() {
        if (closed) { super.stop(); return }
        try {
            execute {
                unregisterOnThread { true }
                closed = true
                User32.INSTANCE.PostQuitMessage(0)
            }
            thread.join(2000)
        } finally { closed = true; super.stop() }
    }

    private fun execute(action: () -> Unit) {
        check(!closed && thread.isAlive) { "Windows hotkey provider is stopped" }
        if (Thread.currentThread() == thread) { action(); return }
        val task = FutureTask(Callable { action(); Unit })
        commands.add(task)
        if (User32.INSTANCE.PostThreadMessage(ready.get(), 0x8001, WPARAM(0), LPARAM(0)) == 0) {
            commands.remove(task)
            error("Unable to wake Windows hotkey message pump")
        }
        try { task.get(5, TimeUnit.SECONDS) }
        catch (e: ExecutionException) { throw IllegalStateException(e.cause?.message, e.cause) }
        catch (e: Exception) { task.cancel(false); throw e }
    }

    private fun messageLoop() {
        try {
            val message = MSG()
            User32.INSTANCE.PeekMessage(message, null, 0, 0, 0) // create the thread's message queue
            ready.complete(Kernel32.INSTANCE.GetCurrentThreadId())
            while (!closed) {
                val result = User32.INSTANCE.GetMessage(message, null, 0, 0)
                check(result != -1) { "Windows GetMessage failed" }
                if (result == 0) break
                when (message.message) {
                    0x8001 -> while (true) (commands.poll() ?: break).run()
                    0x0312 -> hotkeys[message.wParam.toInt()]?.let { fireEvent(it) }
                }
            }
        } catch (e: Exception) {
            ready.completeExceptionally(e)
            System.err.println("Windows hotkey message pump failed: ${e.message}")
        } finally {
            unregisterOnThread { true }
            closed = true
            while (true) (commands.poll() ?: break).cancel(false)
        }
    }
}

internal fun windowsModifiers(modifiers: Int): Int {
    var result = 0x4000 // MOD_NOREPEAT: holding a shortcut must not repeatedly copy/translate.
    if (modifiers and InputEvent.ALT_DOWN_MASK != 0) result = result or 1
    if (modifiers and InputEvent.CTRL_DOWN_MASK != 0) result = result or 2
    if (modifiers and InputEvent.SHIFT_DOWN_MASK != 0) result = result or 4
    if (modifiers and InputEvent.META_DOWN_MASK != 0) result = result or 8
    return result
}

internal fun windowsVirtualKey(key: Int): Int = when (key) {
    KeyEvent.VK_ENTER -> 0x0D
    KeyEvent.VK_INSERT -> 0x2D
    KeyEvent.VK_DELETE -> 0x2E
    KeyEvent.VK_PRINTSCREEN -> 0x2C
    KeyEvent.VK_WINDOWS, KeyEvent.VK_META -> 0x5B
    KeyEvent.VK_CONTEXT_MENU -> 0x5D
    KeyEvent.VK_COMMA -> 0xBC
    KeyEvent.VK_PERIOD -> 0xBE
    KeyEvent.VK_SEMICOLON -> 0xBA
    KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS -> 0xBB
    KeyEvent.VK_MINUS -> 0xBD
    KeyEvent.VK_SLASH -> 0xBF
    KeyEvent.VK_BACK_QUOTE -> 0xC0
    KeyEvent.VK_OPEN_BRACKET -> 0xDB
    KeyEvent.VK_BACK_SLASH -> 0xDC
    KeyEvent.VK_CLOSE_BRACKET -> 0xDD
    KeyEvent.VK_QUOTE -> 0xDE
    in KeyEvent.VK_F13..KeyEvent.VK_F24 -> 0x7C + key - KeyEvent.VK_F13
    else -> key.also { require(it in 1..255) { "Unsupported Windows shortcut key: $key" } }
}
