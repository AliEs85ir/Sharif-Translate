package org.shariftranslate.core.plugin

import org.shariftranslate.api.core.Logger
import org.shariftranslate.core.plugin.storage.PluginKeyValueStore
import org.shariftranslate.core.plugin.settings.PluginSettingsManager
import org.shariftranslate.core.shared.logging.LoggerFactory
import org.shariftranslate.api.plugin.*
import org.shariftranslate.api.settings.*
import com.github.michaelbull.result.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.*

class PluginInfrastructureTest {
    class TestSettings : PluginSettings.Configurable() {
        @field:Setting(label = "Endpoint", type = SettingType.TEXT)
        var endpoint = "https://default.example/v1"
        @field:Setting(label = "Model", type = SettingType.TEXT)
        var model = "default-model"
        @field:Setting(label = "Key", type = SettingType.PASSWORD)
        var apiKey = ""
    }
    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
    @Test fun deletingPluginDataAllowsReinstallInSameProcess() = runBlocking {
        val store = PluginKeyValueStore(Files.createTempDirectory("plugin-store").toFile())
        store.storeValue("test-plugin", "key", "before")
        store.deleteAllData("test-plugin")
        assertNull(store.getValue("test-plugin", "key"))
        store.storeValue("test-plugin", "key", "after")
        assertEquals("after", store.getValue("test-plugin", "key"))
    }
    @Test fun invalidIdsAndMissingProvidersDoNotLockInspectedJars() {
        val dir = Files.createTempDirectory("plugin-loader")
        val loader = PluginLoader(logger)
        for (id in listOf("../outside", "a/b", "C:\\escape", "", "valid-plugin")) {
            val jar = Files.createTempFile(dir, "plugin", ".jar").toFile()
            JarOutputStream(jar.outputStream()).use {
                it.putNextEntry(JarEntry("plugin.json"))
                val escaped = id.replace("\\", "\\\\")
                it.write("""{"id":"$escaped","name":"Test","version":"1","author":"Test","description":"Test","minApiVersion":"1.0.0"}""".toByteArray())
                it.closeEntry()
            }
            assertEquals(id == "valid-plugin", loader.getManifestFromJar(jar) != null)
            assertNull(loader.loadPluginFromFile(jar))
            assertTrue(jar.delete(), "Inspected JAR must be deletable on Windows")
        }
    }

    @Test fun partialSettingsPreserveCredentialsAndRejectedChangesDoNotPersist(): Unit = runBlocking {
        val store = PluginKeyValueStore(Files.createTempDirectory("plugin-partial-settings").toFile())
        val manager = PluginSettingsManager(store, object : LoggerFactory {
            override fun getLogger(name: String) = logger
        })
        val plugin = object : Plugin<TestSettings> {
            var active = TestSettings()
            override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> = Ok(Unit)
            override fun getServices(): List<Service> = emptyList()
            override fun getSettings() = active
            override suspend fun onSettingsChanged(settings: TestSettings): Result<Unit, ServiceError> {
                if (!settings.endpoint.startsWith("https://")) return Err(ServiceError.ValidationError("Invalid endpoint"))
                active = settings
                return Ok(Unit)
            }
        }
        assertTrue(manager.applySettings("test", plugin, mapOf("endpoint" to "https://user.example/v1", "model" to "user-model", "apiKey" to "user-key")).isOk)
        val before = plugin.active
        assertTrue(manager.applySettings("test", plugin, mapOf("endpoint" to "https://new.example/v1")).isOk)
        assertEquals("user-model", plugin.active.model)
        assertEquals("user-key", plugin.active.apiKey)
        assertEquals("https://user.example/v1", before.endpoint, "Live settings must not be mutated before validation")
        assertEquals("user-key", store.getValue("test", "apiKey"))
        assertTrue(manager.applySettings("test", plugin, mapOf("endpoint" to "invalid", "apiKey" to "other-key")).isErr)
        assertEquals("https://new.example/v1", plugin.active.endpoint)
        assertEquals("user-key", plugin.active.apiKey)
        assertEquals("https://new.example/v1", store.getValue("test", "endpoint"))
        assertEquals("user-key", store.getValue("test", "apiKey"))
    }
}
