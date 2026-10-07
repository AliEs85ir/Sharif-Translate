package org.shariftranslate.plugins.google

import org.shariftranslate.api.core.Logger
import org.shariftranslate.api.plugin.*
import org.shariftranslate.api.language.LanguageCode
import org.shariftranslate.api.spellchecker.SpellCheckRequest
import org.shariftranslate.api.tts.*
import org.shariftranslate.plugins.common.*
import org.shariftranslate.plugins.google.common.GoogleLanguageMapper
import com.github.michaelbull.result.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class GoogleSpellCheckerServiceTest {
    private fun context() = object : PluginContext {
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

    @Test fun layoutLanguageCacheOffsetsAndBoundedNetworkRequests(): Unit = runBlocking {
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val failures = AtomicInteger()
        val workers = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = workers
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            val concurrent = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, concurrent) }
            try {
                val query = exchange.requestURI.rawQuery.split('&').associate {
                    val pair = it.split('=', limit = 2)
                    pair[0] to URLDecoder.decode(pair[1], "UTF-8")
                }
                Thread.sleep(25)
                val text = query.getValue("q")
                val corrected = if (query["sl"] == "en") text.replace("teh", "the") else text
                val failed = text == "retry" && failures.getAndIncrement() == 0
                val body = if (failed) "offline" else buildJsonObject {
                    if (corrected != text) putJsonObject("spell") { put("spell_res", corrected) }
                }.toString()
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(if (failed) 503 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { active.decrementAndGet() }
        }
        server.start()
        val context = context()
        val http = KtorHttpClient(context, config = HttpClientConfig(enableRetry = false))
        val checker = GoogleSpellCheckerService(context, http, GoogleLanguageMapper, ApiConfig(), "http://127.0.0.1:${server.address.port}/")
        suspend fun check(text: String, language: LanguageCode = LanguageCode.ENGLISH) = checker.check(SpellCheckRequest(text, language))
        try {
            val layout = " \tHello.\r\n  Bye!  😀 سلام\n\nLast line\t "
            assertEquals(layout, check(layout).getOrElse { error(it.toString()) }.correctedText)
            val before = calls.get()
            assertEquals(layout, check(layout).getOrElse { error(it.toString()) }.correctedText)
            assertEquals(before, calls.get(), "Successful checks should be cached")
            assertEquals("teh teh", check("teh teh", LanguageCode.FARSI).getOrElse { error(it.toString()) }.correctedText)
            val corrections = check("teh teh").getOrElse { error(it.toString()) }
            assertEquals("the the", corrections.correctedText)
            assertEquals(listOf(0, 4), corrections.corrections.map { it.startIndex })
            assertEquals(listOf(3, 7), corrections.corrections.map { it.endIndex })
            val multi = check("😀 teh.\r\n  teh!").getOrElse { error(it.toString()) }
            assertEquals("😀 the.\r\n  the!", multi.correctedText)
            for (item in multi.corrections) assertEquals(item.original, "😀 teh.\r\n  teh!".substring(item.startIndex, item.endIndex))
            val document = (1..30).joinToString("\n") { "Sentence $it." }
            assertEquals(document, check(document).getOrElse { error(it.toString()) }.correctedText)
            assertTrue(peak.get() in 2..4, "Expected bounded concurrent requests, observed ${peak.get()}")
            assertIs<ServiceError.ServiceUnavailableError>(check("retry").getError())
            assertEquals("retry", check("retry").getOrElse { error(it.toString()) }.correctedText, "Failures must not poison the cache")
        } finally { http.close(); server.stop(0); workers.shutdownNow(); context.scope.cancel() }
    }

    @Test fun speechChunksLongWordsAndRecoversViaFallback(): Unit = runBlocking {
        val received = java.util.Collections.synchronizedList(mutableListOf<String>())
        val fallback = java.util.concurrent.atomic.AtomicBoolean(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.split('&').associate {
                val pair = it.split('=', limit = 2)
                pair[0] to URLDecoder.decode(pair[1], "UTF-8")
            }
            val text = query.getValue("q")
            assertTrue(text.length <= 200)
            assertFalse(Character.isHighSurrogate(text.last()))
            assertFalse(Character.isLowSurrogate(text.first()))
            val code = if (fallback.get() && query["client"] == "gtx") 503 else 200
            if (code == 200) received += text
            exchange.sendResponseHeaders(code, 2)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2)) }
        }
        server.start()
        val context = context()
        val http = KtorHttpClient(context, config = HttpClientConfig(enableRetry = false))
        val tts = GoogleTTSService(context, http, GoogleLanguageMapper, ApiConfig(), "http://127.0.0.1:${server.address.port}")
        val text = "a".repeat(199) + "😀".repeat(250)
        try {
            val audio = tts.synthesize(TTSRequest.ByLanguage(text, LanguageCode.ENGLISH)).getOrElse { error(it.toString()) }.audio as TTSAudio.Bytes
            assertEquals(text, received.joinToString(""))
            assertEquals(received.size * 2, audio.data.size)
            received.clear()
            fallback.set(true)
            val retried = tts.synthesize(TTSRequest.ByLanguage(text, LanguageCode.ENGLISH)).getOrElse { error(it.toString()) }.audio as TTSAudio.Bytes
            assertEquals(text, received.joinToString(""))
            assertEquals(received.size * 2, retried.data.size)
        } finally { http.close(); server.stop(0); context.scope.cancel() }
    }

    @Test fun rateLimitedWebClientFallsBackOnceAndKeepsQueryFeatures(): Unit = runBlocking {
        val clients = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.split('&').map {
                val pair = it.split('=', limit = 2)
                pair[0] to URLDecoder.decode(pair[1], "UTF-8")
            }
            val client = query.first { it.first == "client" }.second
            clients += client
            assertEquals(listOf("qc", "md"), query.filter { it.first == "dt" }.map { it.second })
            assertEquals("سلام 😀", query.first { it.first == "q" }.second)
            val code = when (exchange.requestURI.path) {
                "/auth" -> 401
                "/limited" -> 429
                else -> if (client == "gtx") 429 else 200
            }
            val body = """{"spell":{"spell_res":"result"}}""".toByteArray()
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val context = context()
        val http = KtorHttpClient(context, config = HttpClientConfig(enableRetry = false))
        val url = "http://127.0.0.1:${server.address.port}"
        val params = mapOf("client" to "gtx", "q" to "سلام 😀", "dt" to listOf("qc", "md"))
        try {
            assertTrue(googleWebRequest(http, url, emptyMap(), params).isOk)
            assertEquals(listOf("gtx", "dict-chrome-ex"), clients)
            clients.clear()
            assertIs<ServiceError.AuthenticationError>(googleWebRequest(http, "$url/auth", emptyMap(), params).getError())
            assertEquals(listOf("gtx"), clients)
            clients.clear()
            assertIs<ServiceError.RateLimitError>(googleWebRequest(http, "$url/limited", emptyMap(), params).getError())
            assertEquals(listOf("gtx", "dict-chrome-ex"), clients)
        } finally { http.close(); server.stop(0); context.scope.cancel() }
    }
}
