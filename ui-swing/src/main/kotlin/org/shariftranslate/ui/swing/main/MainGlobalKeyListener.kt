package org.shariftranslate.ui.swing.main

import org.shariftranslate.core.settings.data.HotkeyAction
import org.shariftranslate.core.settings.data.HotkeyBinding
import org.shariftranslate.core.settings.data.HotkeyScope
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import com.tulskiy.keymaster.common.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Robot
import java.awt.Toolkit
import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import org.shariftranslate.ui.swing.shared.util.NativeInputHook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages global and local hotkey registration.
 *
 * ### Scopes
 * - [HotkeyScope.GLOBAL] — registered with jKeymaster, fires system-wide
 *   even when SharifTranslate is not focused.
 * - [HotkeyScope.LOCAL] — the caller (MainAppFrame) registers these via
 *   Swing InputMap/ActionMap; this listener only provides the binding list
 *   via [getLocalBindings]. Local bindings never intercept keys from other apps.
 *
 * ### Per-action scope (Dinar's request)
 * Users can choose per action whether it should be global or local. This prevents
 * shortcuts like Ctrl+L from being stolen from browsers when set to LOCAL.
 *
 * ### Actions
 * - [HotkeyAction.SHOW_MAIN_WINDOW] — special: uses double-Ctrl via JNativeHook,
 *   not a regular KeyStroke. Always GLOBAL. Respects [isEnabled] on the binding.
 * - [HotkeyAction.REPLACE_WITH_TRANSLATION] — copies selected text, translates,
 *   pastes result back via [onReplaceWithTranslation].
 * - [HotkeyAction.CYCLE_TARGET_LANGUAGE] — default LOCAL, cycles the target language.
 */
class MainGlobalKeyListener(
    private val scope: CoroutineScope,
    private val onShowApp: (String) -> Unit,
    private val onShowQuickTranslate: (String) -> Unit,
    private val onListenToText: (String) -> Unit,
    private val onOpenSnippingTool: () -> Unit,
    private val onReplaceWithTranslation: (String) -> Unit,
    private val onCycleTargetLanguage: () -> Unit,
    private val onShowDictionary: (String) -> Unit = {},
    private val onTranslate: () -> Unit = {},
    private val onFocusPanel: (HotkeyAction) -> Unit = {}
) {

    private var provider: Provider? = null
    private var nativeHookRegistered = false
    private val sequenceListener = CustomSequenceListener()
    private val clipboardLock = AtomicBoolean(false)
    private val hotkeysEnabled = AtomicBoolean(true)
    // AtomicBoolean.compareAndSet prevents double-initialization if initialize()
    // is called concurrently (e.g. from two rapid lifecycle events).
    private val initialized = AtomicBoolean(false)

    @Volatile private var bindings: List<HotkeyBinding> = HotkeyBinding.DEFAULTS

    @Synchronized fun initialize() {
        if (initialized.compareAndSet(false, true)) {
            try {
                initJKeyMaster()
            } catch (e: Exception) {
                initialized.set(false)   // allow retry if initialization itself failed
                System.err.println("Hotkey initialization failed: ${e.message}")
            }
        }
        // Native-hook failure must not disable regular registered shortcuts.
        try { initJNativeHook() }
        catch (e: Exception) { System.err.println("Native key hook failed: ${e.message}") }
    }

    @Synchronized fun updateBindings(newBindings: List<HotkeyBinding>) {
        if (bindings == newBindings) return
        bindings = newBindings.toList()
        if (!initialized.get()) return
        try {
            provider?.reset()
            registerGlobalHotkeys()
        } catch (e: Exception) {
            System.err.println("Failed to update hotkey bindings: ${e.message}")
        }
    }

    @Synchronized fun setHotkeysEnabled(enabled: Boolean) {
        if (hotkeysEnabled.getAndSet(enabled) == enabled) return
        if (!initialized.get()) return
        if (enabled) enableHotkeys() else disableHotkeys()
    }

    fun areHotkeysEnabled(): Boolean = hotkeysEnabled.get()

    /**
     * Returns bindings with [HotkeyScope.LOCAL] scope that are enabled and have a key.
     * The caller (MainAppFrame) registers these via Swing InputMap.
     */
    fun getLocalBindings(): List<HotkeyBinding> =
        bindings.filter { it.scope == HotkeyScope.LOCAL && it.isEnabled && it.hasBinding }

    @Synchronized fun shutdown() {
        runCatching { provider?.reset() }
        runCatching { provider?.stop() }
        provider = null
        if (nativeHookRegistered) {
            GlobalScreen.removeNativeKeyListener(sequenceListener)
            runCatching { NativeInputHook.release() }
            nativeHookRegistered = false
        }
        initialized.set(false)
    }

    private fun initJKeyMaster() {
        provider = if (Platform.isWindows()) WindowsHotkeyProvider() else Provider.getCurrentProvider(false)
            ?: throw Exception("Hotkey provider unavailable")
        registerGlobalHotkeys()
    }

    /**
     * Registers only [HotkeyScope.GLOBAL] bindings with jKeymaster.
     * LOCAL bindings are handled by MainAppFrame via Swing InputMap.
     *
     * Each binding is wrapped in its own try-catch so a single failure
     * (e.g. OS refuses to grant a reserved key combination) does not
     * silently abort registration of the remaining bindings.
     */
    private fun registerGlobalHotkeys() {
        if (!hotkeysEnabled.get()) return
        val p = provider ?: return

        bindings
            .filter { it.scope == HotkeyScope.GLOBAL && it.isEnabled && it.hasBinding }
            .forEach { binding ->
                val keyStroke = binding.toKeyStroke() ?: return@forEach
                val action = binding.action
                runCatching {
                    p.register(keyStroke) {
                        if (!hotkeysEnabled.get()) return@register
                        if (binding !in bindings) return@register // Discard callbacks queued before a rebind.
                        dispatchAction(action)
                    }
                    println("[Hotkeys] Registered GLOBAL ${action.name}: $keyStroke")
                }.onFailure { ex ->
                    System.err.println(
                        "[Hotkeys] Failed to register GLOBAL ${action.name} ($keyStroke): ${ex.message}"
                    )
                }
            }
    }

    /**
     * Dispatches an action from either a global hotkey or a local InputMap trigger.
     * Called from both [registerGlobalHotkeys] and MainAppFrame's local key handler.
     */
    fun dispatchAction(action: HotkeyAction) {
        when (action) {
            HotkeyAction.SHOW_QUICK_TRANSLATE ->
                launchSelectedText(onShowQuickTranslate)
            HotkeyAction.LISTEN_TO_TEXT ->
                launchSelectedText(onListenToText)
            HotkeyAction.OPEN_OCR ->
                onOpenSnippingTool()
            HotkeyAction.SHOW_MAIN_WINDOW ->
                launchSelectedText(onShowApp)
            HotkeyAction.REPLACE_WITH_TRANSLATION ->
                launchSelectedText(onReplaceWithTranslation)
            HotkeyAction.CYCLE_TARGET_LANGUAGE ->
                onCycleTargetLanguage()
            HotkeyAction.SHOW_DICTIONARY ->
                launchSelectedText(onShowDictionary)
            HotkeyAction.TRANSLATE ->
                onTranslate()
            HotkeyAction.FOCUS_INPUT,
            HotkeyAction.FOCUS_OUTPUT,
            HotkeyAction.FOCUS_EXTRA_OUTPUT -> onFocusPanel(action)
        }
    }

    private fun enableHotkeys() {
        try { provider?.reset(); registerGlobalHotkeys() }
        catch (e: Exception) { System.err.println("Enable hotkeys failed: ${e.message}") }
    }

    private fun disableHotkeys() {
        try { provider?.reset() }
        catch (e: Exception) { System.err.println("Disable hotkeys failed: ${e.message}") }
    }

    private fun initJNativeHook() {
        if (!nativeHookRegistered) {
            NativeInputHook.acquire()
            GlobalScreen.addNativeKeyListener(sequenceListener)
            nativeHookRegistered = true
        }
    }

    /**
     * Handles the double-Ctrl sequence for [HotkeyAction.SHOW_MAIN_WINDOW].
     *
     * This cannot be expressed as a single KeyStroke so it uses JNativeHook's
     * raw key events. It only fires if:
     * 1. Global hotkeys are enabled
     * 2. The SHOW_MAIN_WINDOW binding exists AND isEnabled = true
     *    (Hoyeun's request — user can disable it from the keyboard panel)
     */
    private inner class CustomSequenceListener : NativeKeyListener {
        private val taps = CtrlTapSequence()

        private fun enabled(): Boolean {
            val binding = bindings.find { it.action == HotkeyAction.SHOW_MAIN_WINDOW }
            return hotkeysEnabled.get() && !clipboardLock.get() &&
                binding?.isEnabled == true && binding.isDoubleCtrlEnabled
        }

        override fun nativeKeyPressed(e: NativeKeyEvent) {
            if (!enabled()) { taps.reset(); return }
            if (e.modifiers and (NativeKeyEvent.SHIFT_MASK or NativeKeyEvent.ALT_MASK or NativeKeyEvent.META_MASK) != 0) {
                taps.reset()
                return
            }
            taps.pressed(e.keyCode == NativeKeyEvent.VC_CONTROL)
        }

        override fun nativeKeyReleased(e: NativeKeyEvent) {
            if (!enabled()) { taps.reset(); return }
            if (taps.released(e.keyCode == NativeKeyEvent.VC_CONTROL, System.nanoTime()))
                launchSelectedText(onShowApp)
        }
    }

    private fun launchSelectedText(callback: (String) -> Unit) {
        // Claim before launching so rapid repeats cannot queue stale selection captures.
        if (!clipboardLock.compareAndSet(false, true)) return
        val job = scope.launch(Dispatchers.IO) {
            try {
                val text = SelectedTextCapture(
                    Toolkit.getDefaultToolkit().systemClipboard, ::simulateCopy, ::waitForModifiers
                ).capture() ?: return@launch
                callback(text) // The original clipboard is already restored here.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Selected text capture failed: ${e.message}")
            }
        }
        job.invokeOnCompletion { clipboardLock.set(false) }
    }

    private suspend fun waitForModifiers(): Boolean {
        if (!Platform.isWindows()) { delay(80); return true }
        val keys = intArrayOf(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_ALT, KeyEvent.VK_WINDOWS)
        repeat(100) {
            if (keys.none { User32.INSTANCE.GetAsyncKeyState(it).toInt() and 0x8000 != 0 }) return true
            delay(10)
        }
        return false // Avoid Ctrl+Shift+C / Ctrl+Alt+C while shortcut modifiers are held.
    }

    private fun simulateCopy() {
        val robot = Robot()
        robot.autoDelay = 5
        try {
            robot.keyPress(KeyEvent.VK_CONTROL)
            robot.keyPress(KeyEvent.VK_C)
        } finally {
            robot.keyRelease(KeyEvent.VK_C)
            robot.keyRelease(KeyEvent.VK_CONTROL)
        }
    }
}
