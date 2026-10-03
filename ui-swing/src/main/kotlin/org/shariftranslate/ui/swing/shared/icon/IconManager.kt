package org.shariftranslate.ui.swing.shared.icon

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.ScaledImageIcon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.shariftranslate.api.core.Logger
import org.shariftranslate.core.plugin.PluginManager
import org.shariftranslate.ui.swing.shared.theme.ThemeManager
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.UIManager
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

/** Interface icons are resolved through one editable catalog; provider logos stay separate. */
class IconManager(
    private val pluginManager: PluginManager?,
    appDataDirectory: File,
    private val themeManager: ThemeManager,
    private val logger: Logger,
) {
    private val pluginIconCache = java.util.concurrent.ConcurrentHashMap<String, Icon>()
    private val iconsDirectory = File(appDataDirectory, "icons")
    private val customDirectory = File(iconsDirectory, "custom")
    private val configFile = File(iconsDirectory, "icons.json")
    private val roles = listOf(
        "arrow-left", "arrow-right", "arrow-down", "check", "close", "copy-text", "globe", "keyboard",
        "layout-dashboard", "notification", "package", "palette", "pen-line", "pin",
        "scan-text", "settings", "sliders-horizontal", "swap", "text-align-start", "trash",
        "volume", "zap", "book-open", "star", "collection", "languages", "link-2",
        "unlink"
    )
    private val configuration: JsonObject

    init {
        customDirectory.mkdirs()
        if (!configFile.exists()) {
            runCatching { configFile.writeText(defaultConfiguration(), Charsets.UTF_8) }
                .onFailure { logger.warn("Could not create icon settings: ${it.message}") }
        }
        val guide = File(iconsDirectory, "راهنمای-آیکون‌ها.txt")
        if (!guide.exists()) {
            runCatching {
                javaClass.classLoader.getResourceAsStream("icons/hugeicons/guide-fa.txt")?.use {
                    guide.writeBytes(it.readBytes())
                }
            }.onFailure { logger.warn("Could not create icon guide: ${it.message}") }
        }
        configuration = runCatching { Json.parseToJsonElement(configFile.readText(Charsets.UTF_8)).jsonObject }
            .onFailure { logger.warn("Invalid icons.json; using bundled icon defaults: ${it.message}") }
            .getOrDefault(JsonObject(emptyMap()))
        validateColors()
    }

    fun getIcon(path: String, width: Int, height: Int): Icon {
        val role = roleFor(path)
        if (role != null) return loadConfiguredIcon(role, width, height)
        return createIcon(path, width, height, javaClass.classLoader)
    }

    fun getIcon(serviceId: String, path: String, width: Int, height: Int): Icon {
        val cacheKey = "$serviceId:$path:$width:$height"
        return pluginIconCache.getOrPut(cacheKey) {
            val pluginLoader = pluginManager?.getPluginClassLoaderForService(serviceId)
            createIcon(path, width, height, pluginLoader ?: javaClass.classLoader)
        }
    }

    private fun roleFor(path: String): String? {
        if (!path.startsWith("icons/ui/")) return null
        val basename = path.substringAfterLast('/').substringBeforeLast('.')
        return basename.takeIf { it in roles }
    }

    private fun loadConfiguredIcon(role: String, width: Int, height: Int): Icon {
        val entry = (configuration["icons"] as? JsonObject)?.get(role) as? JsonObject
        val requestedFile = (entry?.get("file") as? JsonPrimitive)?.content ?: "bundled/$role.svg"
        val rawWidth = (entry?.get("strokeWidth") as? JsonPrimitive)?.content
        val requestedWidth = rawWidth?.toFloatOrNull()
        val strokeWidth = requestedWidth?.takeIf { it in 0.5f..4f } ?: 1.5f
        if (rawWidth != null && strokeWidth != requestedWidth) {
            logger.warn("Invalid strokeWidth for icon '$role'; using 1.5")
        }
        val selected = runCatching { loadSvgFile(requestedFile, width, height, strokeWidth) }
            .onFailure { logger.warn("Invalid icon '$role' ($requestedFile): ${it.message}; using bundled default") }
            .getOrNull()
        val icon = selected ?: runCatching { loadSvgFile("bundled/$role.svg", width, height, 1.5f) }
            .onFailure { logger.warn("Bundled icon '$role' failed: ${it.message}") }
            .getOrNull() ?: return getMissingIcon(width, height)
        icon.colorFilter = FlatSVGIcon.ColorFilter { resolveColor(entry) }
        return icon
    }

    private fun loadSvgFile(fileName: String, width: Int, height: Int, strokeWidth: Float): FlatSVGIcon {
        val source = when {
            fileName.matches(Regex("bundled/[a-z0-9-]+\\.svg")) ->
                javaClass.classLoader.getResourceAsStream("icons/hugeicons/${fileName.substringAfter('/')}")
                    ?: throw IllegalArgumentException("Bundled SVG does not exist")
            fileName.matches(Regex("custom/[a-zA-Z0-9._-]+\\.svg")) -> {
                val file = File(customDirectory, fileName.substringAfter('/'))
                if (!file.isFile || file.length() > 128_000) throw IllegalArgumentException("Custom SVG is missing or too large")
                file.inputStream()
            }
            else -> throw IllegalArgumentException("Use bundled/name.svg or custom/name.svg")
        }
        val bytes = source.use { it.readBytes() }
        if (bytes.size > 128_000) throw IllegalArgumentException("SVG is too large")
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        if (document.documentElement.localName != "svg" && document.documentElement.tagName != "svg") {
            throw IllegalArgumentException("File is not an SVG")
        }
        updateStrokeWidth(document.documentElement, strokeWidth)
        val output = ByteArrayOutputStream()
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        transformer.transform(DOMSource(document), StreamResult(output))
        val icon = FlatSVGIcon(ByteArrayInputStream(output.toByteArray())).derive(width, height)
        if (icon.iconWidth <= 0 || icon.iconHeight <= 0) throw IllegalArgumentException("SVG cannot be rendered")
        return icon
    }

    private fun updateStrokeWidth(element: Element, width: Float) {
        if (element.hasAttribute("stroke-width")) element.setAttribute("stroke-width", width.toString())
        val children = element.childNodes
        for (index in 0 until children.length) {
            (children.item(index) as? Element)?.let { updateStrokeWidth(it, width) }
        }
    }

    private fun resolveColor(entry: JsonObject?): Color {
        val themeId = themeManager.getCurrentTheme()?.id
        fun fromMap(value: kotlinx.serialization.json.JsonElement?): Color? =
            if (themeId == null) null else ((value as? JsonObject)?.get(themeId) as? JsonPrimitive)
                ?.content?.let { parseColor(it) }
        return fromMap(entry?.get("themeColors"))
            ?: (entry?.get("color") as? JsonPrimitive)?.content?.let { parseColor(it) }
            ?: fromMap(configuration["themeColors"])
            ?: UIManager.getColor("Label.foreground")
            ?: Color.BLACK
    }

    private fun parseColor(value: String): Color? =
        if (value.matches(Regex("#[0-9a-fA-F]{6}"))) Color(value.substring(1).toInt(16)) else null

    private fun validateColors() {
        fun check(value: kotlinx.serialization.json.JsonElement?, location: String) {
            val color = (value as? JsonPrimitive)?.content ?: return
            if (parseColor(color) == null) logger.warn("Invalid icon color at $location: $color")
        }
        fun checkMap(value: kotlinx.serialization.json.JsonElement?, location: String) {
            (value as? JsonObject)?.forEach { (theme, color) -> check(color, "$location.$theme") }
        }
        checkMap(configuration["themeColors"], "themeColors")
        (configuration["icons"] as? JsonObject)?.forEach { (role, value) ->
            val entry = value as? JsonObject ?: return@forEach
            check(entry["color"], "icons.$role.color")
            checkMap(entry["themeColors"], "icons.$role.themeColors")
        }
    }

    private fun defaultConfiguration(): String {
        val entries = roles.joinToString(",\n") { "    \"$it\": { \"file\": \"bundled/$it.svg\", \"strokeWidth\": 1.5 }" }
        return "{\n  \"themeColors\": {},\n  \"icons\": {\n$entries\n  }\n}\n"
    }

    private fun createIcon(path: String, width: Int, height: Int, loader: ClassLoader): Icon = when {
        path.endsWith(".svg", true) -> {
            if (loader.getResource(path) == null) getMissingIcon(width, height)
            else FlatSVGIcon(path, width, height, loader)
        }
        path.endsWith(".png", true) || path.endsWith(".jpg", true) || path.endsWith(".gif", true) -> {
            val resource = loader.getResource(path)
            if (resource == null) getMissingIcon(width, height)
            else ImageIcon(resource).let { if (it.iconWidth > 0) ScaledImageIcon(it, width, height) else getMissingIcon(width, height) }
        }
        else -> getMissingIcon(width, height)
    }

    private fun getMissingIcon(width: Int, height: Int): Icon =
        FlatSVGIcon("ui/icons/missing_icon.svg", width, height, javaClass.classLoader)
}
