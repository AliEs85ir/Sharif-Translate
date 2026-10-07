package org.shariftranslate.plugins.bing

import org.shariftranslate.api.core.Logger
import org.shariftranslate.api.plugin.*
import org.shariftranslate.plugins.common.*
import org.shariftranslate.api.language.LanguageCode
import org.shariftranslate.api.spellchecker.SpellCheckRequest
import org.shariftranslate.api.tts.*
import com.github.michaelbull.result.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Locale
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class BingAuthManagerTest {
    private val page = """<div data-iid = 'translator.1'></div><script>IG : "request-id"; params_AbusePreventionHelper=[123,"token\u0627",1000];</script>"""
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

    @Test fun parsesWhitespaceQuotedAttributesAndEscapedTokens() {
        val parsed = parseBingAuth(page).getOrElse { error(it.toString()) }
        assertEquals("tokenا", parsed.auth.token)
        assertEquals("123", parsed.auth.key)
        assertEquals("translator.1", parsed.auth.iid)
        assertEquals(1000L, parsed.lifetimeMillis)
        for (bad in listOf("<html>captcha</html>", page.replace("123,", "null,"), page.replace("token\\u0627", ""))) {
            assertIs<ServiceError.InvalidResponseError>(parseBingAuth(bad).getError())
        }
        assertEquals(3_600_000L, parseBingAuth(page.replace(",1000]", ",999999999]")).getOrElse { error(it.toString()) }.lifetimeMillis)
    }

    @Test fun concurrentRequestsShareRefreshAndRespectProviderExpiry(): Unit = runBlocking {
        val calls = AtomicInteger()
        val time = AtomicLong(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            Thread.sleep(40)
            val bytes = page.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val context = context()
        val http = KtorHttpClient(context)
        val manager = BingAuthManager(context, http, "http://127.0.0.1:${server.address.port}", time::get)
        try {
            val results = (1..20).map { async { manager.getAuth().getOrElse { error(it.toString()) } } }.awaitAll()
            assertTrue(results.all { it.token == "tokenا" })
            assertEquals(1, calls.get())
            time.set(999)
            manager.getAuth().getOrElse { error(it.toString()) }
            assertEquals(1, calls.get())
            time.set(1000)
            (1..20).map { async { manager.getAuth().getOrElse { error(it.toString()) } } }.awaitAll()
            assertEquals(2, calls.get())
        } finally { http.close(); server.stop(0); context.scope.cancel() }
    }

    @Test fun spellLayoutRepeatedOffsetsAndSpeechSsmlArePreserved(): Unit = runBlocking {
        val speech = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/auth") { exchange ->
            val bytes = page.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/spell") { exchange ->
            val form = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText().split('&').associate {
                val pair = it.split('=', limit = 2)
                pair[0] to URLDecoder.decode(pair[1], "UTF-8")
            }
            assertTrue(form.getValue("text").length <= 1000)
            val bytes = buildJsonObject { put("correctedText", form.getValue("text")) }.toString().toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/speech") { exchange ->
            val form = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText().split('&').associate {
                val pair = it.split('=', limit = 2)
                pair[0] to URLDecoder.decode(pair[1], "UTF-8")
            }
            speech += form.getValue("ssml")
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2)) }
        }
        server.start()
        val context = context()
        val http = KtorHttpClient(context)
        val base = "http://127.0.0.1:${server.address.port}"
        val auth = BingAuthManager(context, http, "$base/auth")
        val checker = BingSpellCheckerService(context, http, auth, BingLanguageMapper, ApiConfig(), "$base/spell")
        val tts = BingTTSService(context, http, auth, BingLanguageMapper, ApiConfig(), "$base/speech")
        val previousLocale = Locale.getDefault()
        try {
            val text = " \tHello.\r\n  " + "😀".repeat(600) + "\nend  "
            assertEquals(text, checker.check(SpellCheckRequest(text, LanguageCode.ENGLISH)).getOrElse { error(it.toString()) }.correctedText)
            val correction = checker.generateCorrections("teh\tand teh", "teh and the").single()
            assertEquals(8, correction.startIndex)
            assertEquals(11, correction.endIndex)
            assertEquals("teh", correction.original)
            Locale.setDefault(Locale.GERMANY)
            val audio = tts.synthesize(TTSRequest.ByLanguage("<& سلام " + "a".repeat(550), LanguageCode.FARSI, 1.25f)).getOrElse { error(it.toString()) }.audio as TTSAudio.Bytes
            assertEquals(speech.size * 2, audio.data.size)
            assertTrue(speech.size > 1)
            assertTrue(speech.all { "rate='+25.00%'" in it })
            assertTrue("&lt;&amp;" in speech.first())
            assertTrue(speech.all { "fa-IR" in it })
        } finally { Locale.setDefault(previousLocale); http.close(); server.stop(0); context.scope.cancel() }
    }
}
