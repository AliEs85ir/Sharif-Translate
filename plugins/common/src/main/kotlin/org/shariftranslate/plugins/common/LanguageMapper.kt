package org.shariftranslate.plugins.common

import org.shariftranslate.api.language.LanguageCode
import org.shariftranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * Maps SharifTranslate LanguageCode to provider-specific language codes.
 */
interface LanguageMapper {
    /**
     * Converts a SharifTranslate LanguageCode to a provider-specific code.
     */
    fun toProviderCode(code: LanguageCode): String

    /**
     * Converts a provider-specific code to a SharifTranslate LanguageCode.
     */
    fun fromProviderCode(providerCode: String): LanguageCode

    /**
     * Fetches supported languages dynamically or returns a static list.
     */
    suspend fun getSupportedLanguages(): Result<Set<LanguageCode>, ServiceError>
}