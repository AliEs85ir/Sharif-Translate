package org.shariftranslate.core.settings

import org.shariftranslate.api.core.Logger
import org.shariftranslate.core.settings.data.SettingsRepository
import org.shariftranslate.core.settings.data.Configuration
import org.shariftranslate.core.settings.mvi.SettingsStore
import org.shariftranslate.core.settings.mvi.SettingsIntent
import org.shariftranslate.core.shared.events.AppEventBus
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class SettingsPersistenceTest {
    @Test fun repeatedSavesWithoutDialogDoNotBlockAndSurviveReload(): Unit = runBlocking {
        val logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = SettingsRepository(Files.createTempDirectory("settings-validation").toFile(), Json, logger)
        val store = SettingsStore(repository, AppEventBus(), logger, scope, repository.loadInitialConfiguration())
        try {
            withTimeout(8000) {
                repeat(100) { index ->
                    store.dispatch(SettingsIntent.UpdateDraft(store.state.value.workingConfiguration.copy(uiScale = 100 + index)))
                    assertTrue(store.saveChanges().isOk)
                }
            }
            assertEquals(199, repository.loadInitialConfiguration().uiScale)
            assertFalse(store.state.value.isSaving)
            assertFalse(store.state.value.isDirty)
            store.dispatch(SettingsIntent.UpdateDraft(store.state.value.workingConfiguration.copy(interfaceLanguage = "fa-IR")))
            store.dispatch(SettingsIntent.CancelChanges)
            assertEquals("en", store.state.value.workingConfiguration.interfaceLanguage)
        } finally { scope.cancel() }
    }
}
