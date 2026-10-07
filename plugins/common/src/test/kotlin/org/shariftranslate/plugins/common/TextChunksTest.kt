package org.shariftranslate.plugins.common

import kotlin.test.*

class TextChunksTest {
    @Test fun longWordsLayoutAndEmojiRespectProviderLimit() {
        for (limit in listOf(2, 200, 500, 1000)) {
            val text = " \tسلام\n" + "a".repeat(199) + "😀".repeat(550) + "\r\nend  "
            val chunks = textChunks(text, limit)
            assertEquals(text, chunks.joinToString(""))
            assertTrue(chunks.all { it.length <= limit && it.isNotEmpty() })
            assertTrue(chunks.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
        }
        assertEquals(emptyList(), textChunks("", 200))
        assertFailsWith<IllegalArgumentException> { textChunks("text", 1) }
    }
}
