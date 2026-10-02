package org.shariftranslate.core.plugin

import org.shariftranslate.api.core.Logger
import org.shariftranslate.core.plugin.storage.PluginKeyValueStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.*

class PluginInfrastructureTest {
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
}
