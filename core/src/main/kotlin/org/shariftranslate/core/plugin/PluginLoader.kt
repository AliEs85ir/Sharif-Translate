package org.shariftranslate.core.plugin

import org.shariftranslate.api.core.ApiVersion
import org.shariftranslate.api.core.Logger
import org.shariftranslate.api.plugin.Plugin
import org.shariftranslate.core.shared.util.Hashing
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLClassLoader
import java.util.*

/**
 * Handles scanning, loading, and inspecting plugin JAR files from the filesystem.
 *
 * `PluginLoader` is a pure I/O component — it reads JARs and produces [LoadedPluginResult]s.
 * It does not touch the in-memory registry, initialize plugins, or manage state.
 * All of that is the responsibility of [PluginManager] and its collaborators.
 */
class PluginLoader(
    private val logger: Logger
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Scans [directory] for JAR files, loads each one, and returns the results sorted by
     * manifest ID alphabetically — stable and deterministic across all platforms and runs.
     *
     * Why not sort by version? Two plugins at the same version (e.g. both "1.0.0") would
     * have undefined relative order, and updating one plugin could reorder unrelated ones.
     *
     * Why sort JARs by name first? [File.listFiles] returns entries in OS-defined order
     * (hash-table order on Linux ext4, creation order on NTFS, etc.) which is not stable
     * between runs. Sorting filenames before loading ensures consistent processing order.
     */
    fun loadPluginsFromDirectory(directory: File): List<LoadedPluginResult> {
        if (!directory.isDirectory) {
            logger.warn("Plugin directory does not exist or is not a directory: ${directory.absolutePath}")
            return emptyList()
        }
        return directory.listFiles { f -> f.extension == "jar" }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { loadPluginFromFile(it) }
            .sortedBy { it.manifest.id }
    }

    /**
     * Loads and inspects a single plugin JAR file.
     *
     * Steps:
     * 1. Creates a [URLClassLoader] for the JAR.
     * 2. Discovers the [Plugin] implementation via [ServiceLoader].
     * 3. Reads and parses `plugin.json` from the JAR's resources.
     * 4. Verifies API compatibility via [ApiVersion.isCompatible].
     * 5. Computes a SHA-256 hash of the JAR for integrity tracking.
     *
     * @return A [LoadedPluginResult] on success, or `null` if any step fails.
     */
    fun loadPluginFromFile(jarFile: File): LoadedPluginResult? {
        var classLoader: URLClassLoader? = null
        return try {
            // Validate metadata before executing plugin constructors or opening a persistent loader.
            val manifest = getManifestFromJar(jarFile)
                ?: error("plugin.json is missing or invalid in ${jarFile.name}")
            when (val compat = ApiVersion.isCompatible(manifest.minApiVersion)) {
                is ApiVersion.CompatibilityResult.Compatible -> Unit
                is ApiVersion.CompatibilityResult.Incompatible -> error(compat.reason)
            }
            classLoader = URLClassLoader(arrayOf(jarFile.toURI().toURL()), javaClass.classLoader)
            val plugin = ServiceLoader.load(Plugin::class.java, classLoader).firstOrNull()
                ?: error("No Plugin implementation found in ${jarFile.name}")
            LoadedPluginResult(plugin, manifest, jarFile, Hashing.sha256(jarFile), classLoader)
        } catch (e: Exception) {
            classLoader?.close()
            logger.error("Failed to load plugin from ${jarFile.name}: ${e.message}", e)
            null
        } catch (e: ServiceConfigurationError) {
            classLoader?.close()
            logger.error("Invalid plugin provider in ${jarFile.name}", e)
            null
        }
    }

    /**
     * Reads and parses `plugin.json` from a JAR without fully loading the plugin.
     * Useful for manifest-only inspection (e.g. during install validation).
     */
    fun getManifestFromJar(jarFile: File, classLoader: ClassLoader? = null): PluginManifest? =
        runCatching {
            // JarFile.use avoids locking an inspected JAR on Windows and cannot inherit
            // another plugin's manifest from a parent classloader.
            java.util.jar.JarFile(jarFile).use { jar ->
                val entry = jar.getJarEntry("plugin.json") ?: return@use null
                jar.getInputStream(entry).bufferedReader(Charsets.UTF_8).use {
                    json.decodeFromString<PluginManifest>(it.readText()).also { manifest ->
                        require(manifest.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "Invalid plugin ID" }
                        require(!manifest.id.endsWith('.')) { "Invalid plugin ID" }
                    }
                }
            }
        }.getOrNull()
}
