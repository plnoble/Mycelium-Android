package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.BuildConfig
import com.xavierxia.mycelium.net.HttpText
import java.io.IOException
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseClientTest {
    @Test
    fun missingReleaseReturnsNull() {
        val calls = mutableListOf<Triple<String, Map<String, String>, Int>>()
        val client = GitHubReleaseClient { url, headers, timeout ->
            calls += Triple(url, headers, timeout)
            HttpText(404, "")
        }

        assertNull(client.latestRelease())
        assertEquals(
            "https://api.github.com/repos/plnoble/Mycelium-Android/releases/latest",
            calls.single().first,
        )
        assertEquals(10_000, calls.single().third)
        assertEquals("application/vnd.github+json", calls.single().second["Accept"])
        assertEquals("2022-11-28", calls.single().second["X-GitHub-Api-Version"])
        assertEquals("Mycelium-Android/${BuildConfig.VERSION_NAME}", calls.single().second["User-Agent"])
    }

    @Test
    fun publishedReleaseSelectsApkAsset() {
        val client = GitHubReleaseClient { _, _, _ ->
            HttpText(
                200,
                """
                {
                  "tag_name": "v0.0.2",
                  "html_url": "https://github.com/plnoble/Mycelium-Android/releases/tag/v0.0.2",
                  "assets": [
                    {"name": "mycelium.apk.sha256", "browser_download_url": "https://example.com/checksum"},
                    {"name": "mycelium.apk", "browser_download_url": "https://example.com/mycelium-r.apk"}
                  ]
                }
                """.trimIndent(),
            )
        }

        val release = client.latestRelease()!!

        assertEquals("0.0.2", release.versionName)
        assertEquals("v0.0.2", release.tagName)
        assertEquals("https://example.com/mycelium-r.apk", release.apkDownloadUrl)
    }

    @Test
    fun releaseWithoutApkUsesReleasePageForBrowserHandoff() {
        val client = GitHubReleaseClient { _, _, _ ->
            HttpText(200, """{"tag_name":"v0.0.2","html_url":"https://example.com/release","assets":[]}""")
        }

        val release = client.latestRelease()!!

        assertEquals(release.releasePageUrl, release.apkDownloadUrl)
    }

    @Test
    fun httpFailureIsNotMistakenForMissingRelease() {
        for (status in listOf(403, 429, 500)) {
            val error = assertThrows(IllegalStateException::class.java) {
                GitHubReleaseClient { _, _, _ -> HttpText(status, "") }.latestRelease()
            }
            assertEquals("GitHub returned HTTP $status", error.message)
        }
    }

    @Test
    fun malformedResponseIsRejected() {
        assertThrows(JSONException::class.java) {
            GitHubReleaseClient { _, _, _ -> HttpText(200, "not json") }.latestRelease()
        }
    }

    @Test
    fun networkFailurePropagates() {
        val error = assertThrows(IOException::class.java) {
            GitHubReleaseClient { _, _, _ -> throw IOException("Connection timed out") }.latestRelease()
        }
        assertTrue(error.message!!.contains("timed out"))
    }
}
