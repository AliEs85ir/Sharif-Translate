package org.shariftranslate.core.updater

import org.shariftranslate.api.core.Logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import com.github.michaelbull.result.getError
import kotlin.test.*

class IndependentUpdaterTest {
    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    @Test fun missingRepositoryDoesNotAttemptAnUpdate(): Unit = runBlocking {
        val client = HttpClient()
        // A closed client would fail if the updater tried any network request.
        client.close()
        for ((owner, repo) in listOf("" to "", "owner" to "", "" to "repo")) {
            val error = Updater(owner, repo, client, logger).checkForUpdate("1.2.1").getError()
            assertEquals("No update repository configured for Sharif Translate", error?.message)
        }
    }
}
