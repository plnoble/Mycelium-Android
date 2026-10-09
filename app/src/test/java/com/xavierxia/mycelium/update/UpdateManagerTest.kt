package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateManagerTest {
    @Test
    fun github404BecomesNoPublishedRelease() = runBlocking {
        val client = GitHubReleaseClient { url ->
            object : HttpURLConnection(url) {
                override fun getResponseCode() = HTTP_NOT_FOUND
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
            }
        }

        assertEquals(UpdateResult.NoPublishedRelease, UpdateManager(client).checkForUpdate())
    }

    @Test
    fun newerReleaseIsAvailable() = runBlocking {
        val release = release("999.0.0")
        val manager = UpdateManager(ReleaseSource { release })

        assertEquals(UpdateResult.Available(release), manager.checkForUpdate())
    }

    @Test
    fun sameOrOlderReleaseIsUpToDate() = runBlocking {
        for (version in listOf(BuildConfig.VERSION_NAME, "0.0.0")) {
            val manager = UpdateManager(ReleaseSource { release(version) })
            assertEquals(UpdateResult.UpToDate, manager.checkForUpdate())
        }
    }

    @Test
    fun networkFailureBecomesDisplayableError() = runBlocking {
        val manager = UpdateManager(ReleaseSource { throw IOException("Network unavailable") })

        assertEquals(UpdateResult.Error("Network unavailable"), manager.checkForUpdate())
    }

    @Test
    fun errorWithoutMessageHasFallback() = runBlocking {
        val manager = UpdateManager(ReleaseSource { throw IOException() })

        assertEquals(UpdateResult.Error("Unknown error"), manager.checkForUpdate())
    }

    @Test
    fun cancellationIsPropagated() {
        val manager = UpdateManager(ReleaseSource { throw CancellationException("Screen closed") })

        assertThrows(CancellationException::class.java) {
            runBlocking { manager.checkForUpdate() }
        }
    }

    private fun release(version: String) = ReleaseInfo(
        versionName = version,
        tagName = "v$version",
        releasePageUrl = "https://github.com/plnoble/Mycelium-Android/releases/tag/v$version",
        apkDownloadUrl = "https://example.com/mycelium-r.apk"
    )
}
