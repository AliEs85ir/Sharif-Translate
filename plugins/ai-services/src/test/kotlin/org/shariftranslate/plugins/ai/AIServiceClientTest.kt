package org.shariftranslate.plugins.ai

import org.shariftranslate.api.core.Logger
import org.shariftranslate.api.language.LanguageCode
import org.shariftranslate.api.plugin.*
import org.shariftranslate.api.translator.TranslationRequest
import org.shariftranslate.plugins.common.KtorHttpClient
import com.github.michaelbull.result.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class AIServiceClientTest {
    private val context = object : PluginContext {
        override val logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }
        override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        override suspend fun notify(title: String, body: String, type: NotificationType) = Unit
        override suspend fun storeValue(key: String, value: String) = Unit
        override suspend fun getValue(key: String): String? = null
        override suspend fun deleteValue(key: String) = Unit
        override fun getPluginDataDirectory() = File(System.getProperty("java.io.tmpdir"))
    }

    @Test fun realHttpPayloadUnicodeAndInvalidResponses(): Unit = runBlocking {
        val request = AtomicReference<String>()
        val response = AtomicReference("""{"choices":[{"message":{"role":"assistant","content":"سلام!\nخط دوم 😀"},"finish_reason":"stop"}]}""")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            request.set(exchange.requestBody.bufferedReader(Charsets.UTF_8).readText())
            val bytes = response.get().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val http = KtorHttpClient(context)
        val settings = AISettings(baseUrl = " http://127.0.0.1:${server.address.port}/v1/ ", model = "validation-model", customHeaders = "")
        val client = AIServiceClient(context, http) { settings }
        try {
            assertEquals("سلام!\nخط دوم 😀", client.complete("translate", "Hello!\nSecond line 😀").get())
            val body = Json.parseToJsonElement(request.get()).jsonObject
            assertEquals("validation-model", body["model"]?.jsonPrimitive?.content)
            assertEquals("Hello!\nSecond line 😀", body["messages"]!!.jsonArray[1].jsonObject["content"]?.jsonPrimitive?.content)
            assertTrue("max_completion_tokens" in body)
            for (invalid in listOf(
                "{}", "<html>offline</html>",
                """{"choices":[{"message":{"content":null}}]}""",
                """{"choices":[{"message":{"content":" "}}]}""",
                """{"choices":[{"message":{"content":"partial"},"finish_reason":"length"}]}""",
                """{"choices":[{"message":{"refusal":"declined","content":null}}]}"""
            )) {
                response.set(invalid)
                assertIs<ServiceError.InvalidResponseError>(client.complete("translate", "text").getError(), invalid)
            }
            response.set("""{"error":{"message":"Too many requests","code":429}}""")
            assertIs<ServiceError.RateLimitError>(client.complete("translate", "text").getError())
            response.set("""{"choices":[{"message":{"content":"{\"translation\":\"سلام!\\nخط دوم\",\"detected_language\":\"en\"}"}}]}""")
            val translated = AITranslatorService(client).translate(TranslationRequest("Hello\nline two", LanguageCode.AUTO, LanguageCode.FARSI)).get()
            assertEquals("سلام!\nخط دوم", translated?.translatedText)
            assertEquals(LanguageCode.ENGLISH, translated?.detectedLanguage)
            response.set("""{"choices":[{"message":{"content":"{\"translation\":\" \"}"}}]}""")
            assertIs<ServiceError.InvalidResponseError>(AITranslatorService(client).translate(TranslationRequest("Hello", LanguageCode.AUTO, LanguageCode.FARSI)).getError())
        } finally { http.close(); server.stop(0); context.scope.cancel() }
    }

    @Test fun remoteEndpointsRequireCredentialsAndInvalidSettingsFailBeforeNetwork(): Unit = runBlocking {
        val http = KtorHttpClient(context)
        var settings = AISettings()
        val client = AIServiceClient(context, http) { settings }
        try {
            assertIs<ServiceError.AuthenticationError>(client.complete("translate", "test").getError())
            settings = settings.copy(baseUrl = "http://localhost:1/v1", customHeaders = "{invalid}")
            assertIs<ServiceError.InvalidInputError>(client.complete("translate", "test").getError())
            settings = settings.copy(baseUrl = "http://localhost:1/v1?key=test", customHeaders = "")
            assertIs<ServiceError.InvalidInputError>(client.complete("translate", "test").getError())
            settings = settings.copy(baseUrl = "not a URL")
            assertIs<ServiceError.InvalidInputError>(client.complete("translate", "test").getError())
            settings = settings.copy(baseUrl = "http://localhost:1/v1", maxTokens = 0)
            assertIs<ServiceError.InvalidInputError>(client.complete("translate", "test").getError())
        } finally { http.close(); context.scope.cancel() }
    }
}
