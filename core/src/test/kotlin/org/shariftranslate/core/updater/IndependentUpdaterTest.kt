package org.shariftranslate.core.updater

import org.shariftranslate.api.core.Logger
import org.shariftranslate.core.updater.data.GitHubAsset
import org.shariftranslate.core.updater.data.GitHubReleaseResponse
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

    @Test fun releaseDownloadSelectsWindowsPackageRatherThanChecksum() {
        val release = GitHubReleaseResponse(
            tagName = "v0.1.0", name = "Preview", releaseNotes = "",
            assets = listOf(
                GitHubAsset("https://github.com/AliEs85ir/Sharif-Translate/releases/download/v0.1.0/SHA256SUMS.txt"),
                GitHubAsset("https://github.com/AliEs85ir/Sharif-Translate/releases/download/v0.1.0/SharifTranslate-0.1.0-windows-x64.zip")
            )
        )
        assertTrue(release.packageDownloadUrl!!.endsWith(".zip"))
        assertNull(release.copy(assets = release.assets.take(1)).packageDownloadUrl)
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
