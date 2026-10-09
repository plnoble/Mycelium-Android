package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.BuildConfig
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseClientTest {
    @Test
    fun missingReleaseReturnsNullAndClosesConnection() {
        val connection = FakeConnection(404)
        val client = GitHubReleaseClient { url ->
            assertEquals(
                "https://api.github.com/repos/plnoble/Mycelium-Android/releases/latest",
                url.toString()
            )
            connection
        }

        assertNull(client.latestRelease())
        assertFalse(connection.bodyRead)
        assertTrue(connection.disconnected)
        assertEquals("GET", connection.requestMethod)
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(10_000, connection.readTimeout)
        assertEquals("application/vnd.github+json", connection.getRequestProperty("Accept"))
        assertEquals("2022-11-28", connection.getRequestProperty("X-GitHub-Api-Version"))
        assertEquals(
            "Mycelium-Android/${BuildConfig.VERSION_NAME}",
            connection.getRequestProperty("User-Agent")
        )
    }

    @Test
    fun publishedReleaseSelectsApkAsset() {
        val connection = FakeConnection(200, """
            {
              "tag_name": "v0.0.2",
              "html_url": "https://github.com/plnoble/Mycelium-Android/releases/tag/v0.0.2",
              "assets": [
                {"name": "mycelium.apk.sha256", "browser_download_url": "https://example.com/checksum"},
                {"name": "mycelium.apk", "browser_download_url": "https://example.com/mycelium-r.apk"}
              ]
            }
        """.trimIndent())

        val release = GitHubReleaseClient { connection }.latestRelease()!!

        assertEquals("0.0.2", release.versionName)
        assertEquals("v0.0.2", release.tagName)
        assertEquals("https://example.com/mycelium-r.apk", release.apkDownloadUrl)
        assertTrue(connection.disconnected)
    }

    @Test
    fun releaseWithoutApkUsesReleasePageForBrowserHandoff() {
        val connection = FakeConnection(200, """
            {"tag_name":"v0.0.2","html_url":"https://example.com/release","assets":[]}
        """.trimIndent())

        val release = GitHubReleaseClient { connection }.latestRelease()!!

        assertEquals(release.releasePageUrl, release.apkDownloadUrl)
        assertTrue(connection.disconnected)
    }

    @Test
    fun httpFailureIsNotMistakenForMissingRelease() {
        for (status in listOf(403, 429, 500)) {
            val connection = FakeConnection(status)
            val error = assertThrows(IllegalStateException::class.java) {
                GitHubReleaseClient { connection }.latestRelease()
            }

            assertEquals("GitHub returned HTTP $status", error.message)
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun malformedResponseStillClosesConnection() {
        val connection = FakeConnection(200, "not json")

        assertThrows(org.json.JSONException::class.java) {
            GitHubReleaseClient { connection }.latestRelease()
        }
        assertTrue(connection.disconnected)
    }

    @Test
    fun networkFailureStillClosesConnection() {
        val connection = FakeConnection(200, failure = IOException("Connection timed out"))

        assertThrows(IOException::class.java) {
            GitHubReleaseClient { connection }.latestRelease()
        }
        assertTrue(connection.disconnected)
    }

    private class FakeConnection(
        private val status: Int,
        private val body: String = "",
        private val failure: IOException? = null
    ) : HttpURLConnection(URL("https://api.github.com")) {
        var disconnected = false
        var bodyRead = false

        override fun getResponseCode(): Int {
            failure?.let { throw it }
            return status
        }

        override fun getInputStream(): ByteArrayInputStream {
            bodyRead = true
            return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        }

        override fun disconnect() { disconnected = true }
        override fun connect() = Unit
        override fun usingProxy() = false
    }
}
