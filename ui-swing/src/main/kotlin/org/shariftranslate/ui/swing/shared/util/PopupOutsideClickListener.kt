package org.shariftranslate.ui.swing.shared.util

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent
import com.github.kwhat.jnativehook.mouse.NativeMouseListener
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.*
import javax.swing.event.ChangeListener

/** Only mouse presses outside the dialog, its menus and owned windows dismiss it. */
internal class PopupOutsideClickListener(
    private val window: Window,
    private val isPinned: () -> Boolean,
    private val onDismiss: () -> Unit
) {
    @Volatile private var protectedBounds: List<Rectangle> = emptyList()
    private var active = false
    @Volatile private var generation = 0
    private val menus = MenuSelectionManager.defaultManager()
    private val menuListener = ChangeListener {
        refreshBounds()
        // A menu's selection path can change before its native popup is shown.
        SwingUtilities.invokeLater { if (active) refreshBounds() }
    }
    private val boundsListener = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent) = refreshBounds()
        override fun componentResized(e: ComponentEvent) = refreshBounds()
    }
    private val mouseListener = object : NativeMouseListener {
        override fun nativeMousePressed(e: NativeMouseEvent) {
            // MouseInfo uses Java screen coordinates, including Windows DPI scaling.
            val point = MouseInfo.getPointerInfo()?.location ?: return
            val outside = protectedBounds.none { it.contains(point) }
            val session = generation
            if (outside) SwingUtilities.invokeLater {
                if (active && session == generation && window.isVisible && !isPinned() &&
                    !insideOwnedWindow(window, point)) onDismiss()
            }
        }
    }

    private fun insideOwnedWindow(owner: Window, point: Point): Boolean =
        owner.ownedWindows.any { it.isShowing && (it.bounds.contains(point) || insideOwnedWindow(it, point)) }

    fun start() {
        if (active) return
        try {
            NativeInputHook.acquire()
        } catch (e: Exception) {
            System.err.println("Popup mouse hook failed: ${e.message}")
            return
        }
        generation++
        active = true
        window.addComponentListener(boundsListener)
        menus.addChangeListener(menuListener)
        refreshBounds()
        GlobalScreen.addNativeMouseListener(mouseListener)
    }

    fun stop() {
        if (!active) return
        active = false
        generation++ // Discard events queued before this popup was hidden/reopened.
        GlobalScreen.removeNativeMouseListener(mouseListener)
        window.removeComponentListener(boundsListener)
        menus.removeChangeListener(menuListener)
        protectedBounds = emptyList()
        runCatching { NativeInputHook.release() }
            .onFailure { System.err.println("Popup mouse hook cleanup failed: ${it.message}") }
    }

    private fun refreshBounds() {
        val regions = mutableListOf(Rectangle(window.bounds))
        fun addOwned(owner: Window) {
            owner.ownedWindows.filter { it.isShowing }.forEach {
                regions.add(Rectangle(it.bounds))
                addOwned(it)
            }
        }
        addOwned(window)
        menus.selectedPath.map { it.component }.filter { it.isShowing }.forEach { component ->
            val popup = component as? JPopupMenu
            val invoker = popup?.invoker ?: component
            if (SwingUtilities.getWindowAncestor(invoker) == window) {
                regions.add(Rectangle(component.locationOnScreen, component.size))
            }
        }
        protectedBounds = regions
    }
}
