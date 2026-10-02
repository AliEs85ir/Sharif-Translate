package org.shariftranslate.ui.swing.shared.util

import com.github.kwhat.jnativehook.GlobalScreen

/** Shared ownership: disabling hotkeys must not disable popup outside-click detection. */
internal object NativeInputHook {
    private var users = 0
    private var ownsHook = false

    @Synchronized fun acquire() {
        if (users == 0) {
            ownsHook = !GlobalScreen.isNativeHookRegistered()
            if (ownsHook) GlobalScreen.registerNativeHook()
        }
        users++
    }

    @Synchronized fun release() {
        if (users == 0) return
        users--
        if (users == 0 && ownsHook) {
            GlobalScreen.unregisterNativeHook()
            ownsHook = false
        }
    }
}
