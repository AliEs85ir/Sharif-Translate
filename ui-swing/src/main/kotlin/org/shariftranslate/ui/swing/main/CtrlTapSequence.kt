package org.shariftranslate.ui.swing.main

/** Two standalone Ctrl taps. Ctrl+C/Q/etc. and key repeat never count as taps. */
internal class CtrlTapSequence(private val thresholdNanos: Long = 400_000_000L) {
    private var down = false
    private var standalone = false
    private var lastTap: Long? = null

    fun pressed(isCtrl: Boolean) {
        if (isCtrl) {
            if (!down) standalone = true
            down = true
        } else {
            standalone = false
            lastTap = null
        }
    }

    fun released(isCtrl: Boolean, now: Long): Boolean {
        if (!isCtrl) return false
        val validTap = down && standalone
        down = false
        standalone = false
        if (!validTap) return false
        val previous = lastTap
        val triggered = previous != null && now - previous in 0..thresholdNanos
        lastTap = if (triggered) null else now
        return triggered
    }

    fun reset() {
        down = false
        standalone = false
        lastTap = null
    }
}
