package org.shariftranslate.core.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HistorySnapshot(
    val inputText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translatorId: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val timestamp: Long = System.currentTimeMillis(),
    /** The language actually detected when source was AUTO; null if source was explicit. */
    val detectedSourceLanguage: String? = null,
    /** Extra output text (backward translation, summary, or rewrite) at the time of translation. */
    val extraOutputText: String = "",
    /** Serialized [org.shariftranslate.core.settings.data.ExtraOutputType] name. */
    val extraOutputType: String = "None"
)
