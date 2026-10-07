package org.shariftranslate.plugins.common

/** Bounded UTF-16 chunks whose concatenation exactly reconstructs the input. */
fun textChunks(text: String, maxLength: Int): List<String> {
    require(maxLength >= 2)
    val result = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + maxLength, text.length)
        if (end < text.length) {
            // Prefer a nearby word boundary without losing separators.
            val boundary = (end - 1 downTo start + maxLength / 2).firstOrNull { text[it].isWhitespace() }
            if (boundary != null) end = boundary + 1
            if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
        }
        result += text.substring(start, end)
        start = end
    }
    return result
}
