package org.shariftranslate.plugins.bing

import org.shariftranslate.api.plugin.PluginContext
import org.shariftranslate.api.plugin.ServiceError
import org.shariftranslate.plugins.common.ApiConfig
import org.shariftranslate.plugins.common.KtorHttpClient
import com.github.michaelbull.result.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Shares one token refresh across translation, speech and spell-check requests. */
class BingAuthManager(
    private val pluginContext: PluginContext,
    private val httpClient: KtorHttpClient,
    private val pageUrl: String = "https://www.bing.com/translator",
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val refreshMutex = Mutex()
    private var cached: AuthState? = null

    suspend fun getAuth(): Result<BingAuth, ServiceError> = refreshMutex.withLock {
        cached?.takeIf { clock() < it.expiresAt }?.let { return@withLock Ok(it.auth) }
        pluginContext.logger.debug("Refreshing Bing authentication")
        val html = httpClient.get(pageUrl, ApiConfig().createHeaders()).getOrElse { return@withLock Err(it) }
        val parsed = parseBingAuth(html).getOrElse { return@withLock Err(it) }
        cached = AuthState(parsed.auth, clock() + parsed.lifetimeMillis)
        Ok(parsed.auth)
    }

    private data class AuthState(val auth: BingAuth, val expiresAt: Long)
}

internal data class ParsedBingAuth(val auth: BingAuth, val lifetimeMillis: Long)

/** Only reads page fields; never evaluates provider JavaScript. */
internal fun parseBingAuth(html: String): Result<ParsedBingAuth, ServiceError> = try {
    fun field(pattern: String, name: String): String =
        Regex(pattern).find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: error("Missing Bing $name")

    val ig = field("""\bIG\s*:\s*["']([^"']+)["']""", "IG")
    val iid = field("""data-iid\s*=\s*["']([^"']+)["']""", "IID")
    val helper = field("""params_AbusePreventionHelper\s*=\s*(\[[^;]*])\s*;""", "token data")
    val values = Json.parseToJsonElement(helper).jsonArray
    require(values.size >= 2) { "Incomplete Bing token data" }
    val key = values[0].jsonPrimitive.content
    val token = values[1].jsonPrimitive.content
    require(key.isNotBlank() && key.all(Char::isDigit) && token.isNotBlank()) { "Invalid Bing token data" }
    fun optional(name: String) = Regex("""["']$name["']\s*:\s*["']([^"']*)["']""")
        .find(html)?.groupValues?.get(1).orEmpty()
    val lifetime = values.getOrNull(2)?.jsonPrimitive?.longOrNull
        ?.takeIf { it > 0 }?.coerceAtMost(3_600_000L) ?: 3_600_000L
    Ok(ParsedBingAuth(BingAuth(ig, iid, key, token, optional("muid"), optional("sid"), optional("tid")), lifetime))
} catch (e: Exception) {
    Err(ServiceError.InvalidResponseError("Bing authentication page has missing or invalid token fields.", e))
}
