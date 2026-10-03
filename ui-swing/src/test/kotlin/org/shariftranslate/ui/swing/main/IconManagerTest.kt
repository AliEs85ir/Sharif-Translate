package org.shariftranslate.ui.swing.main

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.shariftranslate.api.core.Logger
import org.shariftranslate.ui.swing.shared.icon.IconManager
import org.shariftranslate.ui.swing.shared.theme.ThemeManager
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.swing.Icon
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IconManagerTest {
    private val logger = object : Logger {
        override fun debug(message: String) {}
        override fun info(message: String) {}
        override fun warn(message: String) {}
        override fun error(message: String, error: Throwable?) {}
    }

    @Test fun bundledIconsAndCustomOverridesRenderAcrossThemesAndSizes() {
        SwingUtilities.invokeAndWait {
            val original = UIManager.getLookAndFeel()
            val data = Files.createTempDirectory("sharif-icons-").toFile()
            try {
                val themes = ThemeManager(data, logger)
                val defaults = IconManager(null, data, themes, logger)
                val config = data.resolve("icons/icons.json")
                assertTrue(config.isFile)
                assertTrue(data.resolve("icons/راهنمای-آیکون‌ها.txt").isFile)

                for (dark in listOf(false, true)) {
                    if (dark) FlatDarkLaf.setup() else FlatLightLaf.setup()
                    val allRoles = Json.parseToJsonElement(config.readText()).jsonObject["icons"]!!.jsonObject.keys
                    assertTrue(allRoles.size >= 25)
                    for (name in allRoles) {
                        val image = render(defaults.getIcon("icons/ui/$name.svg", 16, 16))
                        assertTrue(opaquePixels(image) > 0, "$name did not render")
                    }
                    for (name in listOf("arrow-left", "arrow-down", "settings", "copy-text", "languages", "star", "collection")) {
                        for (size in listOf(13, 16, 24, 36)) {
                            val image = render(defaults.getIcon("icons/ui/$name.svg", size, size))
                            assertEquals(size, image.width)
                            assertTrue(opaquePixels(image) > 0, "$name did not render at $size")
                        }
                    }
                }

                val custom = data.resolve("icons/custom/my-arrow.svg")
                custom.writeText("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M3 12h18" fill="none" stroke="#000000" stroke-width="1.5"/></svg>""")
                config.writeText("""{
                    "themeColors": { "builtin:flat_dark": "#445566" },
                    "icons": { "arrow-left": { "file": "custom/my-arrow.svg", "strokeWidth": 3,
                      "themeColors": { "builtin:flat_light": "#112233" } } }
                }""")
                val customIcons = IconManager(null, data, themes, logger)
                themes.applyThemeForStartup(themes.findThemeById("builtin:flat_light"))
                val sharedIcon = customIcons.getIcon("icons/ui/arrow-left.svg", 24, 24)
                val light = render(sharedIcon)
                assertEquals(Color(0x112233).rgb and 0xffffff, firstOpaqueRgb(light))
                themes.applyThemeForStartup(themes.findThemeById("builtin:flat_dark"))
                val dark = render(sharedIcon)
                assertEquals(Color(0x445566).rgb and 0xffffff, firstOpaqueRgb(dark))
                assertTrue(opaquePixels(dark) > 20)

                config.writeText("""{"icons":{"arrow-left":{"file":"custom/my-arrow.svg","strokeWidth":1}}}""")
                val thin = render(IconManager(null, data, themes, logger).getIcon("icons/ui/arrow-left.svg", 24, 24))
                assertTrue(opaquePixels(dark) > opaquePixels(thin), "Stroke width override had no effect")

                config.writeText("""{"icons":{"arrow-left":{"file":"custom/missing.svg"}}}""")
                val fallback = render(IconManager(null, data, themes, logger).getIcon("icons/ui/arrow-left.svg", 24, 24))
                assertTrue(opaquePixels(fallback) > 0)
                custom.writeText("<svg><path")
                config.writeText("""{"icons":{"arrow-left":{"file":"custom/my-arrow.svg"}}}""")
                val malformed = render(IconManager(null, data, themes, logger).getIcon("icons/ui/arrow-left.svg", 24, 24))
                assertTrue(opaquePixels(malformed) > 0)
            } finally {
                UIManager.setLookAndFeel(original)
                data.deleteRecursively()
            }
        }
    }

    private fun render(icon: Icon): BufferedImage =
        BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB).also { image ->
            image.createGraphics().also { graphics -> icon.paintIcon(null, graphics, 0, 0); graphics.dispose() }
        }

    private fun opaquePixels(image: BufferedImage): Int =
        (0 until image.width).sumOf { x -> (0 until image.height).count { y -> image.getRGB(x, y) ushr 24 > 0 } }

    private fun firstOpaqueRgb(image: BufferedImage): Int =
        (0 until image.width).flatMap { x -> (0 until image.height).map { y -> image.getRGB(x, y) } }
            .first { it ushr 24 == 255 } and 0xffffff
}
