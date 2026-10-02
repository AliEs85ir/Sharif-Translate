package org.shariftranslate.core.shared.util

import org.shariftranslate.api.dictionary.Dictionary
import org.shariftranslate.api.ocr.OCR
import org.shariftranslate.api.plugin.Service
import org.shariftranslate.api.rewriter.Rewriter
import org.shariftranslate.api.spellchecker.SpellChecker
import org.shariftranslate.api.summarizer.Summarizer
import org.shariftranslate.api.translator.Translator
import org.shariftranslate.api.tts.TextToSpeech
import org.shariftranslate.core.shared.arch.ServiceType

/** Maps a Service to its ServiceType based on implemented interfaces. */
fun mapServiceToType(service: Service): ServiceType? {
    return when (service) {
        is Translator -> ServiceType.TRANSLATOR
        is TextToSpeech -> ServiceType.TTS
        is OCR -> ServiceType.OCR
        is SpellChecker -> ServiceType.SPELL_CHECKER
        is Dictionary -> ServiceType.DICTIONARY
        is Summarizer -> ServiceType.SUMMARIZER
        is Rewriter -> ServiceType.REWRITER
        else -> null
    }
}

val Service.type get() = mapServiceToType(this)