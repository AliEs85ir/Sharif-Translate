package org.shariftranslate.app

import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.text.JTextComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.shariftranslate.api.language.LanguageCode
import org.shariftranslate.core.main.mvi.*
import org.shariftranslate.core.settings.data.*
import org.shariftranslate.core.settings.mvi.SettingsIntent
import org.shariftranslate.ui.swing.main.MainAppFrame
import org.shariftranslate.ui.swing.main.input.InputTextPanel
import org.shariftranslate.ui.swing.main.output.OutputTextPanel
import org.shariftranslate.ui.swing.settings.SettingsDialog

/** Explicit desktop integration test. Run only with the packaged JAR and runtime. */
object SystemAuditUiSmoke {
    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    private fun descendants(c: Component): List<Component> = listOf(c) +
        if (c is Container) c.components.flatMap { descendants(it) } else emptyList()
    @JvmStatic fun main(args: Array<String>) {
        try { runBlocking { runAudit(args) }; kotlin.system.exitProcess(0) }
        catch (e: Throwable) { e.printStackTrace(); kotlin.system.exitProcess(1) }
    }
    private suspend fun runAudit(args: Array<String>) {
        val data = File(args[0]).absoluteFile.also { it.mkdirs() }
        val scale = args.getOrNull(1)?.toInt() ?: 100
        val locale = args.getOrNull(2) ?: "en-GB"
        System.setProperty("appData", data.path)
        AppDataDirectory.installBundledResources(data)
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> errors.add(e); e.printStackTrace() }
        val logs = ConsoleLoggerFactory(ConsoleLoggerFactory.LogLevel.WARN)
        val repo = SettingsRepository(data, Json { ignoreUnknownKeys = true }, logs.getLogger("Audit"))
        val config = Configuration.DEFAULT.copy(uiScale = scale, interfaceLanguage = locale,
            themeId = if (locale.startsWith("fa")) "builtin:flat_dark" else "builtin:flat_light",
            isGlobalHotkeysEnabled = false, isSpellCheckingEnabled = false, autoCheckForUpdates = false,
            preferredSourceLanguage = "en", preferredTargetLanguage = "fa")
        check(repo.updateConfiguration(config).isOk)
        val deps = buildDependencies(data, logs, repo, config)
        deps.pluginManager.loadAndProcessPlugins()
        deps.localizationManager.loadLanguage(LanguageCode(locale))
        lateinit var frame: MainAppFrame
        edt {
            AppUiSetup.apply(config, deps.themeManager)
            frame = MainAppFrame(deps.mainStore, deps.settingsStore, deps.iconManager, deps.themeManager,
                deps.pluginManager, deps.localizationManager, deps.notificationBus, deps.collectionRepository)
        }
        delay(700)
        fun capture(window: Window, name: String) = edt {
            window.validate()
            val img = BufferedImage(window.width, window.height, BufferedImage.TYPE_INT_ARGB)
            img.createGraphics().also { window.paintAll(it); it.dispose() }
            ImageIO.write(img, "png", File(data, "$name.png"))
        }
        try {
            check(MainStore::class.java.protectionDomain.codeSource.location.path.endsWith("SharifTranslate.jar"))
            capture(frame, "main-initial")
            val input = descendants(frame).filterIsInstance<InputTextPanel>().single().textPaneComponent as JTextComponent
            val before = deps.mainStore.state.value.history
            edt { input.text = "Hello world.\nThis is a UI translation test — 😀" }
            val translateLabel = deps.localizationManager.getString("main_window.translate_button")
            // The language bar's action is identified by its actual visible label.
            val button = descendants(frame).filterIsInstance<JButton>().firstOrNull {
                it.text in setOf("Translate", "ترجمه", translateLabel)
            } ?: error("Translation button not found")
            edt { button.doClick() }
            withTimeout(45000) { deps.mainStore.state.first { !it.isLoading && it.history != before && it.translatedText.isNotBlank() } }
            delay(250)
            edt { check(input.componentOrientation.isLeftToRight) { "English input inherited RTL chrome orientation" } }
            capture(frame, "main-translated")
            println("PASS: UI input -> translation button -> live service -> rendered result ($scale%, $locale)")
            for (layout in listOf("classic", "side_by_side", "compact")) {
                deps.settingsStore.dispatch(SettingsIntent.UpdateDraft(deps.settingsStore.state.value.workingConfiguration.copy(layoutPresetId = layout)))
                delay(350)
                edt { frame.validate() }
                if (layout != "compact") edt {
                    val output = descendants(frame).filterIsInstance<OutputTextPanel>().single()
                    check(output.height >= 50) { "Output clipped: ${output.size} in $layout" }
                }
                capture(frame, "layout-$layout")
                for (extra in listOf(ExtraOutputType.BackwardTranslate, ExtraOutputType.None)) {
                    deps.settingsStore.dispatch(SettingsIntent.UpdateDraft(deps.settingsStore.state.value.workingConfiguration.copy(extraOutputType = extra)))
                    delay(120)
                }
            }
            println("PASS: all three layouts and extra-output visibility transitions")
            lateinit var dialog: SettingsDialog
            edt { dialog = SettingsDialog(frame, deps.settingsStore, deps.pluginManager, deps.iconManager,
                deps.themeManager, deps.localizationManager, { deps.mainStore.state.value.availableLanguages }) }
            edt { dialog.applyComponentOrientation(if (deps.localizationManager.isRtl) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT) }
            SwingUtilities.invokeLater { dialog.isVisible = true }
            delay(300)
            val tree = descendants(dialog).filterIsInstance<JTree>().single()
            for (row in 0 until 8) {
                edt { tree.setSelectionRow(row) }
                delay(180)
                capture(dialog, "settings-$row")
            }
            edt { dialog.dispose() }
            println("PASS: all eight settings pages opened and rendered")
            for (method in listOf("showHistoryDialog", "showCollectionsDialog", "showDictionaryDialog", "onShowAboutDialog")) {
                val open = MainAppFrame::class.java.getDeclaredMethod(method).apply { isAccessible = true }
                if (method == "showDictionaryDialog") edt { frame.isVisible = false }
                SwingUtilities.invokeLater { open.invoke(frame) }
                delay(400)
                val windows = Window.getWindows().filterIsInstance<JDialog>().filter { it.isVisible }
                check(windows.isNotEmpty()) { "$method did not open a dialog" }
                windows.forEachIndexed { index, window -> capture(window, "$method-$index") }
                edt { windows.forEach { it.dispose() }; frame.isVisible = true }
            }
            println("PASS: History, Collections, Dictionary and About dialogs")
            check(errors.isEmpty()) { "Uncaught UI exceptions: $errors" }
            println("SYSTEM UI AUDIT PASSED")
        } finally {
            edt { frame.windowListeners.forEach { frame.removeWindowListener(it) }; Window.getWindows().forEach { it.dispose() }; if (SystemTray.isSupported()) SystemTray.getSystemTray().trayIcons.forEach { SystemTray.getSystemTray().remove(it) } }
            deps.mainStore.onShutdown(); deps.pluginManager.shutdown(); deps.appScope.cancel()
        }
    }
}
