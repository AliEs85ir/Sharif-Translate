package org.shariftranslate.plugins.google

import org.shariftranslate.api.language.LanguageCode
import org.shariftranslate.api.plugin.ServiceError
import com.github.michaelbull.result.*
import kotlinx.serialization.json.*
import kotlin.test.*

class GoogleFallbackResponseTest {
    @Test fun explicitSourceAndAutoDetectBothAcceptRealResponseShapes() {
        val explicit = parseGoogleFallbackResponse(Json.parseToJsonElement("""["سلام!\nخط دوم 😀"]""").jsonArray).get()
        assertEquals("سلام!\nخط دوم 😀", explicit?.translatedText)
        assertNull(explicit?.detectedLanguage)
        val auto = parseGoogleFallbackResponse(Json.parseToJsonElement("""[["سلام","en"]]""").jsonArray).get()
        assertEquals("سلام", auto?.translatedText)
        assertEquals(LanguageCode.ENGLISH, auto?.detectedLanguage)
    }

    @Test fun emptyAndMalformedResponsesAreErrors() {
        for (body in listOf("[]", "[[]]", "[null]", "[123]", "[\"\"]", "[{}]")) {
            assertIs<ServiceError.InvalidResponseError>(parseGoogleFallbackResponse(Json.parseToJsonElement(body).jsonArray).getError())
        }
    }
}
