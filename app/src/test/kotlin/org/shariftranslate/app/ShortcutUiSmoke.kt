package org.shariftranslate.app

import com.formdev.flatlaf.FlatLightLaf
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.shariftranslate.api.language.LanguageCode
import org.shariftranslate.core.settings.data.*
import org.shariftranslate.ui.swing.main.MainGlobalKeyListener
import org.shariftranslate.ui.swing.quciktranslate.*
import org.shariftranslate.ui.swing.dictionary.*
import java.awt.*
import java.awt.datatransfer.*
import java.awt.event.*
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.*

/** Explicit desktop smoke test; uses an isolated external JVM as the selected-text application. */
object ShortcutUiSmoke {
    private const val selected = "Selected text — متن فارسی 😀"

    @JvmStatic fun main(args: Array<String>) {
        if (args.firstOrNull() == "source") { sourceWindow(); return }
        try { runSmoke(args) }
        catch (e: Throwable) { e.printStackTrace(); kotlin.system.exitProcess(1) }
    }

    private fun runSmoke(args: Array<String>) {
        val data = File(args[0]).absoluteFile.also { it.mkdirs() }
        val logs = ConsoleLoggerFactory(ConsoleLoggerFactory.LogLevel.WARN)
        val repository = SettingsRepository(data, Json { ignoreUnknownKeys = true }, logs.getLogger("ShortcutSmoke"))
        val config = Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false, autoCheckForUpdates = false)
        val deps = runBlocking { buildDependencies(data, logs, repository, config) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val captures = LinkedBlockingQueue<String>()
        val cycles = LinkedBlockingQueue<Long>()
        var state = QuickTranslateDialogState(
            isVisible = true, isLoading = true, translatedText = "", sourceText = selected,
            isPinned = false, isFavorite = false, collections = emptyList(),
            sourceLanguage = LanguageCode.ENGLISH, targetLanguage = LanguageCode.FARSI,
            availableLanguages = listOf(LanguageCode.ENGLISH, LanguageCode.FARSI),
            translatorSelectorState = QuickTranslateSelectorState(emptyList(), null),
            actionsState = QuickTranslateActionsState(true, true),
            config = DialogConfig(FontConfig("Dialog", 15), FontConfig("Dialog", 15),
                false, false, 5, 1, Size(530, 260), Position(500, 40)),
            strings = DialogStrings("Copy", "Listen", "Pin", "Unpin", "Loading", "More", "Less",
                "Favorite", "Remove", "Collection", "Manage", "New", "Swap", "Auto", "Original", "Edit", "Translator")
        )
        lateinit var dialog: QuickTranslateDialog
        lateinit var owner: JFrame
        var dictionary: QuickDictionaryDialog? = null
        SwingUtilities.invokeAndWait {
            FlatLightLaf.setup()
            owner = JFrame("Shortcut smoke owner")
            dialog = QuickTranslateDialog(owner, deps.iconManager,
                onDismiss = { state = state.copy(isVisible = false); dialog.render(state) },
                onTranslatorSelected = {}, onListen = {}, onCopy = {}, onOriginalTextChanged = {},
                onTranslateEditedText = {}, onSourceLanguageSelected = {}, onTargetLanguageSelected = {},
                onSwapLanguages = {}, onSavePosition = {}, onSaveSize = {},
                onPinToggled = { state = state.copy(isPinned = !state.isPinned); dialog.render(state) },
                onFavoriteToggled = {}, onCollectionToggled = {}, onNewCollection = {}, onManageCollections = {})
        }
        val listener = MainGlobalKeyListener(scope, {}, { text ->
            captures.offer(text)
            if (text.isNotBlank()) SwingUtilities.invokeLater {
                state = state.copy(isVisible = true, isPinned = false, sourceText = text)
                dialog.render(state)
            }
        }, {}, {}, {}, { cycles.offer(System.nanoTime()) })
        listener.updateBindings(listOf(HotkeyBinding(HotkeyAction.SHOW_QUICK_TRANSLATE,
            KeyEvent.VK_F11, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
            HotkeyBinding(HotkeyAction.CYCLE_TARGET_LANGUAGE, KeyEvent.VK_F12,
                InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK)))
        listener.initialize()
        val java = File(System.getProperty("java.home"), "bin/java.exe").path
        val source = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
            ShortcutUiSmoke::class.java.name, "source").start()
        val input = source.outputStream.bufferedWriter()
        val output = source.inputStream.bufferedReader()
        val robot = Robot().apply { autoDelay = 20 }
        fun command(value: String) {
            input.write(value); input.newLine(); input.flush()
            check(output.readLine() == "READY") { "External selection fixture failed" }
            Thread.sleep(150)
        }
        fun click(x: Int, y: Int) {
            robot.mouseMove(x, y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(200)
            SwingUtilities.invokeAndWait {}
        }
        fun visible(expected: Boolean) {
            SwingUtilities.invokeAndWait { check(dialog.isVisible == expected) { "Popup visible=${dialog.isVisible}, expected=$expected" } }
        }
        fun shortcut(): String {
            val start = System.nanoTime()
            robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(KeyEvent.VK_SHIFT)
            robot.keyPress(KeyEvent.VK_F11); robot.keyRelease(KeyEvent.VK_F11)
            robot.keyRelease(KeyEvent.VK_SHIFT); robot.keyRelease(KeyEvent.VK_CONTROL)
            val text = captures.poll(5, TimeUnit.SECONDS) ?: error("Shortcut did not dispatch")
            println("Shortcut capture latency: ${(System.nanoTime() - start) / 1_000_000} ms")
            Thread.sleep(150)
            SwingUtilities.invokeAndWait {}
            return text
        }
        try {
            check(output.readLine() == "READY")
            command("SELECT")
            robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(KeyEvent.VK_ALT)
            val dispatchStart = System.nanoTime()
            robot.keyPress(KeyEvent.VK_F12); robot.keyRelease(KeyEvent.VK_F12)
            robot.keyRelease(KeyEvent.VK_ALT); robot.keyRelease(KeyEvent.VK_CONTROL)
            val dispatched = cycles.poll(2, TimeUnit.SECONDS) ?: error("Direct shortcut did not dispatch")
            println("Native shortcut dispatch latency: ${(dispatched - dispatchStart) / 1_000_000} ms")
            click(80, 55)
            command("SELECT")
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("clipboard sentinel"), null)
            val captured = shortcut()
            input.write("STATE"); input.newLine(); input.flush()
            println("External fixture: ${output.readLine()}; captured characters=${captured.length}")
            check(captured == selected) { "External fixture selection was not captured" }
            check(Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) == "clipboard sentinel")
            visible(true)
            // Exceed the old timeout while loading, with the pointer outside the popup.
            robot.mouseMove(50, 50)
            Thread.sleep(1600)
            visible(true)
            SwingUtilities.invokeAndWait { state = state.copy(isLoading = false, translatedText = "ترجمه آزمایشی"); dialog.render(state) }
            command("FOCUS") // Focus change alone must not close the popup.
            Thread.sleep(1600)
            visible(true)
            click(730, 165) // inside popup
            visible(true)
            robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE)
            visible(true)
            // A heavyweight menu extending beyond the popup remains part of the popup.
            lateinit var menu: JPopupMenu
            var menuPoint = Point()
            SwingUtilities.invokeAndWait {
                menu = JPopupMenu().apply { add(JMenuItem("Menu option")) }
                menu.show(dialog.contentPane, 400, 280)
                menuPoint = Point(menu.locationOnScreen.x + 20, menu.locationOnScreen.y + 12)
            }
            click(menuPoint.x, menuPoint.y)
            visible(true)
            SwingUtilities.invokeAndWait { menu.isVisible = false }
            listener.setHotkeysEnabled(false) // outside-click hook must remain active
            click(80, 55) // click in the external application
            visible(false)
            println("PASS: slow loading, idle, focus changes, inside/menu clicks, external click with hotkeys disabled")

            listener.setHotkeysEnabled(true)
            command("SELECT")
            check(shortcut() == selected)
            visible(true)
            SwingUtilities.invokeAndWait { state = state.copy(isPinned = true); dialog.render(state) }
            click(80, 55)
            visible(true)
            SwingUtilities.invokeAndWait { state = state.copy(isPinned = false); dialog.render(state) }
            click(80, 55)
            visible(false)
            command("CLEAR")
            check(shortcut().isEmpty())
            visible(false)
            println("PASS: reopen, pin/unpin, and no-selection shortcut does not reuse the clipboard")
            lateinit var dictionaryState: QuickDictionaryDialogState
            SwingUtilities.invokeAndWait {
                dictionary = QuickDictionaryDialog(owner, deps.iconManager)
                dictionaryState = QuickDictionaryDialogState(
                    isVisible = true, isLoading = true, entries = emptyList(), lookedUpWord = "test",
                    hasFailed = false, isPinned = false, availableDictionaries = emptyList(), selectedDictionaryId = null,
                    config = QuickDictionaryConfig(false, Size(420, 240), Position(500, 350), idleTimeoutSeconds = 1),
                    strings = QuickDictionaryStrings("Dictionary", "Hint", "Loading", "Missing", "Error",
                        "Search", "Synonyms", "Pin", "Unpin", "Close"),
                    onLookup = {}, onDictionarySelected = {}, onPinToggled = {}, onClose = {
                        dictionaryState = dictionaryState.copy(isVisible = false)
                        dictionary!!.render(dictionaryState)
                    }, onSavePosition = {}, onSaveSize = {})
                dictionary!!.render(dictionaryState)
            }
            Thread.sleep(1600)
            SwingUtilities.invokeAndWait { check(dictionary!!.isVisible) }
            click(80, 55)
            SwingUtilities.invokeAndWait { check(!dictionary!!.isVisible) }
            println("PASS: dictionary popup persists during loading and dismisses on an external click")
            println("SHORTCUT UI SMOKE PASSED")
        } finally {
            source.destroy()
            listener.shutdown()
            SwingUtilities.invokeAndWait { dictionary?.dispose(); dialog.dispose(); owner.dispose() }
            scope.cancel()
            runBlocking { deps.mainStore.onShutdown() }
            deps.appScope.cancel()
        }
        kotlin.system.exitProcess(0)
    }

    private fun sourceWindow() {
        lateinit var frame: JFrame
        lateinit var text: JTextArea
        SwingUtilities.invokeAndWait {
            frame = JFrame("External selected-text fixture")
            text = JTextArea(selected)
            frame.add(text)
            frame.setBounds(40, 40, 420, 240)
            frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            frame.isVisible = true
            text.selectAll()
            text.requestFocusInWindow()
        }
        println("READY")
        System.`in`.bufferedReader().forEachLine { command ->
            if (command == "STATE") {
                SwingUtilities.invokeAndWait { println("focus=${text.isFocusOwner}, selected=${text.selectedText?.length ?: 0}") }
                return@forEachLine
            }
            SwingUtilities.invokeAndWait {
                frame.toFront()
                text.requestFocusInWindow()
                if (command == "SELECT") text.selectAll()
                if (command == "CLEAR") text.select(0, 0)
            }
            println("READY")
        }
    }
}
