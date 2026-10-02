package org.shariftranslate.app

import java.io.File

/**
 * Resolves the directory where SharifTranslate stores all persistent data
 * (settings, plugins, history, themes, etc.).
 *
 * ### Strategy: JAR-relative first, OS-standard fallback
 *
 * SharifTranslate is designed as a portable app — the data folder lives next to
 * the JAR so the entire installation can be moved or backed up as one unit.
 *
 * ```
 * SharifTranslate/
 *   ├── SharifTranslate.jar
 *   ├── plugins/
 *   ├── themes/
 *   ├── datastore/
 *   └── plugins_data/
 * ```
 *
 * If the JAR's parent directory is not writable (e.g. installed in
 * `C:\Program Files`), the OS-standard location is used as a fallback
 * so the app still works without elevated permissions.
 *
 * | Platform | Fallback path |
 * |----------|---------------|
 * | Windows  | `%APPDATA%\SharifTranslate` |
 * | macOS    | `~/Library/Application Support/SharifTranslate` |
 * | Linux    | `$XDG_CONFIG_HOME/SharifTranslate` or `~/.config/SharifTranslate` |
 */
object AppDataDirectory {

    private const val APP_NAME = "Sharif Translate"

    /**
     * Returns the resolved app data directory, creating it if it does not exist.
     */
    fun resolve(): File {
        System.getProperty("appData")?.let {
            val file = File(it)
            return file.also { f -> f.mkdirs() }
        }

        val jarDir = installationDirectory()
        if (jarDir != null && jarDir.canWrite()) {
            return jarDir.also { it.mkdirs() }
        }
        return osFallback().also { it.mkdirs() }
    }

    /**
     * Returns the directory containing the running JAR, or `null` if the
     * location cannot be determined (e.g. running from an IDE or test runner).
     */
    fun installationDirectory(): File? = runCatching {
        val uri = AppDataDirectory::class.java
            .protectionDomain
            .codeSource
            .location
            .toURI()
        val location = File(uri)
        if (!location.isFile) return@runCatching null
        val parent = location.parentFile
        if (parent.name == "app" && File(parent.parentFile, "runtime").isDirectory) parent.parentFile else parent
    }.getOrNull()

    /** Keep bundled resources available when data falls back to APPDATA or an explicit override. */
    fun installBundledResources(appData: File) {
        val installation = installationDirectory() ?: return
        if (installation.canonicalFile == appData.canonicalFile) return
        for (name in listOf("plugins", "languages", "themes")) {
            val source = File(installation, name)
            if (!source.isDirectory) continue
            source.walkTopDown().filter { it.isFile }.forEach { bundled ->
                val target = File(File(appData, name), bundled.relativeTo(source).path)
                if (!target.exists()) {
                    target.parentFile.mkdirs()
                    bundled.copyTo(target)
                }
            }
        }
    }

    private fun osFallback(): File {
        val base = when {
            os().contains("win") ->
                System.getenv("APPDATA")
                    ?: (System.getProperty("user.home") + "\\AppData\\Roaming")
            os().contains("mac") ->
                System.getProperty("user.home") + "/Library/Application Support"
            else ->
                System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                    ?: (System.getProperty("user.home") + "/.config")
        }
        return File(base, APP_NAME)
    }

    private fun os(): String =
        System.getProperty("os.name").orEmpty().lowercase()
}
