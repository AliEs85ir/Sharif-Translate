package org.shariftranslate.app

import org.shariftranslate.api.language.LanguageCode
import org.shariftranslate.core.settings.data.Configuration
import org.shariftranslate.core.settings.data.SettingsRepository
import org.shariftranslate.core.shared.AppConstants
import org.shariftranslate.ui.swing.main.MainAppFrame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import javax.swing.SwingUtilities

fun main() = runBlocking {

    var frame: MainAppFrame? = null

    if (!SingleInstanceGuard.tryLock(onFocusRequested = {
            SwingUtilities.invokeLater {
                frame?.apply {
                    isVisible = true
                    toFront()
                    requestFocus()
                }
            }
        })) {
        return@runBlocking
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        SingleInstanceGuard.release()
    })

    AppUiSetup.setSystemProperties()
    AppUiSetup.setRenderingHints()

    val appData    = AppDataDirectory.resolve()
    AppDataDirectory.installBundledResources(appData)
    val logFactory = ConsoleLoggerFactory(ConsoleLoggerFactory.LogLevel.DEBUG)
    val logger     = logFactory.getLogger("Main")

    logger.info("Sharif Translate ${AppConstants.APP_VERSION} starting...")
    logger.info("App data directory: ${appData.absolutePath}")

    val json         = Json { ignoreUnknownKeys = true; isLenient = true }
    val settingsRepo = SettingsRepository(appData, json, logFactory.getLogger("SettingsRepository"))

    val initialConfig = withTimeoutOrNull(AppConstants.CONFIG_LOAD_TIMEOUT_MS) {
        settingsRepo.loadInitialConfiguration()
    } ?: run {
        logger.warn("Configuration load timed out after ${AppConstants.CONFIG_LOAD_TIMEOUT_MS}ms — using defaults")
        Configuration.DEFAULT
    }

    logger.info("Configuration loaded: theme=${initialConfig.themeId}, scale=${initialConfig.uiScale}")

    val deps = buildDependencies(
        appData       = appData,
        loggerFactory = logFactory,
        settingsRepo  = settingsRepo,
        initialConfig = initialConfig
    )
    Runtime.getRuntime().addShutdownHook(Thread({
        runBlocking {
            withTimeoutOrNull(5000) {
                deps.settingsStore.saveChanges()
                deps.mainStore.onShutdown()
                deps.pluginManager.shutdown()
            }
        }
        deps.appScope.cancel()
    }, "Sharif Translate-Shutdown"))
    AppUiSetup.apply(initialConfig, deps.themeManager)

    logger.info("Loading plugins...")
    runCatching { deps.pluginManager.loadAndProcessPlugins() }
        .onSuccess { logger.info("Plugins loaded successfully") }
        .onFailure { e -> logger.error("Failed to load plugins", e) }

    val savedLanguage = if (initialConfig.interfaceLanguage == LanguageCode.ENGLISH.tag) {
        OsLanguageDetector.detect(deps.localizationManager.availableLanguages)
    } else {
        LanguageCode(initialConfig.interfaceLanguage)
    }
    runCatching {
        deps.localizationManager.loadLanguage(savedLanguage)
        logger.info("Interface language loaded: ${initialConfig.interfaceLanguage}")
    }.onFailure { e ->
        logger.warn("Failed to load interface language '${initialConfig.interfaceLanguage}': ${e.message}")
    }

    SwingUtilities.invokeLater {
        frame = MainAppFrame(
            mainStore        = deps.mainStore,
            settingsStore    = deps.settingsStore,
            iconManager      = deps.iconManager,
            themeManager     = deps.themeManager,
            localizer        = deps.localizationManager,
            pluginManager    = deps.pluginManager,
            notificationBus  = deps.notificationBus,
            collectionRepository = deps.collectionRepository
        )
        logger.info("Main window launched")
    }

    Thread.currentThread().join()
}
