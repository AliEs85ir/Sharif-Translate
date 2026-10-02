package org.shariftranslate.plugins.common

import org.shariftranslate.api.core.Logger
import org.shariftranslate.api.plugin.*
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.get
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.*

class KtorHttpClientTest {
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

    @Test fun unicodeAndHttpFailures(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val code = exchange.requestURI.path.drop(1).toIntOrNull() ?: 200
            val body = "سلام!\nHello — 😀".toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=UTF-8")
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val client = KtorHttpClient(context, config = HttpClientConfig(enableRetry = false))
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            assertEquals("سلام!\nHello — 😀", client.get(base).get())
            assertIs<ServiceError.AuthenticationError>(client.get("$base/401").getError())
            assertIs<ServiceError.RateLimitError>(client.post("$base/429").getError())
            assertIs<ServiceError.ServiceUnavailableError>(client.get("$base/503").getError())
            assertIs<ServiceError.NetworkError>(client.get("http://127.0.0.1:1/").getError())
        } finally { client.close(); server.stop(0); context.scope.cancel() }
    }

    @Test fun cancelledParsingPropagates(): Unit = runBlocking {
        val parser = JsonResponseParser<String>(context, { throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> { parser.parse("{}") }
    }

    @Test fun cancelledRequestsAndTimeoutAreDistinct(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            Thread.sleep(500)
            runCatching { exchange.sendResponseHeaders(200, 2); exchange.responseBody.use { it.write("ok".toByteArray()) } }
        }
        server.start()
        val client = KtorHttpClient(context, config = HttpClientConfig(requestTimeoutMillis = 80, enableRetry = false))
        val url = "http://127.0.0.1:${server.address.port}"
        try {
            assertIs<ServiceError.TimeoutError>(client.get(url).getError())
            val request = async { client.post(url, body = "{}") }
            delay(20)
            request.cancel()
            assertFailsWith<CancellationException> { request.await() }
        } finally { client.close(); server.stop(0); context.scope.cancel() }
    }

    @Test fun malformedJsonIsAnInvalidResponse(): Unit = runBlocking {
        assertIs<ServiceError.InvalidResponseError>(createJsonParser<List<String>>(context).parse("<html>error</html>").getError())
    }
}
