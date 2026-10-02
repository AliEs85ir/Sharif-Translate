package com.github.ahatem.qtranslate.core.localization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PersianLocalizationTest {
    @Test
    fun bundledPersianTranslationIsCompleteAndRtl() {
        val parser = LanguageTomlParser()
        val persian = javaClass.getResourceAsStream("/localization/fa-IR.toml")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { parser.parse(it.readText()) }
        assertNotNull(persian)
        assertEquals("فارسی", persian.meta?.nativeName)
        assertEquals(true, persian.meta?.isRtl)

        val english = javaClass.getResourceAsStream("/localization/embedded_en.toml")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { parser.parse(it.readText()) }
        assertNotNull(english)
        assertTrue(persian.entries.keys.containsAll(english.entries.keys))
        assertEquals("تنظیمات", persian.entries["settings_dialog.title"])
        assertEquals("ترجمه", persian.entries["main_window_language_bar.translate_button"])
    }
}
