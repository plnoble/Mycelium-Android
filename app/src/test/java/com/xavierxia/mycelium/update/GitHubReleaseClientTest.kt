package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.BuildConfig
import com.xavierxia.mycelium.net.HttpText
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全部用假响应体，不联网。响应形状照真实 GitHub `releases/latest` 造：
 * 附件名来自发版流程实际产出的 `mycelium-v<版本>.apk` / `mycelium-v<版本>.apk.sha256`（实施方案 1.1 实测）。
 */
class GitHubReleaseClientTest {
    @Test
    fun selectsAssetByExactTagName() {
        val client = clientReturning(
            200,
            latestReleaseJson(
                assets = listOf(
                    asset("checksums.txt", "https://example.com/checksums.txt"),
                    asset("mycelium-v0.4.0.apk.sha256", CHECKSUM_URL, size = 86L),
                    asset("mycelium-v0.4.0.apk", APK_URL, size = APK_SIZE),
                ),
            ),
        )

        val release = client.latestRelease()!!

        assertEquals("0.4.0", release.versionName)
        assertEquals("v0.4.0", release.tagName)
        assertEquals("https://github.com/plnoble/Mycelium-Android/releases/tag/v0.4.0", release.releasePageUrl)
        assertEquals(APK_URL, release.apkUrl)
        assertEquals(CHECKSUM_URL, release.sha256Url)
        assertEquals(APK_SIZE, release.apkSizeBytes)
        assertEquals(RELEASE_NOTES, release.releaseNotes)
        assertEquals(PUBLISHED_AT, release.publishedAt)
    }

    @Test
    fun assetOrderDoesNotMatter() {
        // 目标 .apk 排在 .sha256 之后，前面还放了另一个命名规则的 .apk 干扰项：顺序和干扰项都不该影响结果。
        val client = clientReturning(
            200,
            latestReleaseJson(
                assets = listOf(
                    asset("mycelium-android-0.4.0.apk", "https://example.com/distractor.apk"),
                    asset("mycelium-v0.4.0.apk.sha256", CHECKSUM_URL),
                    asset("mycelium-v0.4.0.apk", APK_URL),
                ),
            ),
        )

        val release = client.latestRelease()!!

        assertEquals(APK_URL, release.apkUrl)
        assertEquals(CHECKSUM_URL, release.sha256Url)
    }

    @Test
    fun exactNameWinsOverLongerSimilarName() {
        val client = clientReturning(
            200,
            latestReleaseJson(
                assets = listOf(
                    asset("mycelium-v0.4.0.apk.sha256.txt", "https://example.com/wrong-checksum"),
                    asset("mycelium-v0.4.0.apk", APK_URL),
                    asset("mycelium-v0.4.0.apk.sha256", CHECKSUM_URL),
                ),
            ),
        )

        val release = client.latestRelease()!!

        assertEquals(CHECKSUM_URL, release.sha256Url)
    }

    @Test
    fun missingChecksumAssetDisablesInAppInstall() {
        val client = clientReturning(
            200,
            latestReleaseJson(assets = listOf(asset("mycelium-v0.4.0.apk", APK_URL, size = APK_SIZE))),
        )

        val release = client.latestRelease()!!

        // 缺 .sha256 就无法校验：两个地址一起留空，界面只能给发布页，不提供应用内安装。
        assertNull(release.apkUrl)
        assertNull(release.sha256Url)
        // 大小仍然是这条发布的事实，只是这一版不给装。
        assertEquals(APK_SIZE, release.apkSizeBytes)
    }

    @Test
    fun missingApkAssetDisablesInAppInstall() {
        val client = clientReturning(
            200,
            latestReleaseJson(assets = listOf(asset("mycelium-v0.4.0.apk.sha256", CHECKSUM_URL))),
        )

        val release = client.latestRelease()!!

        assertNull(release.apkUrl)
        assertNull(release.sha256Url)
        assertNull(release.apkSizeBytes)
    }

    @Test
    fun similarAssetNamesAreNotSelectedAsEitherAsset() {
        // 都是「像但不是」：预发布版的附件、别的版本、别的命名规则。一个都不许选中。
        val client = clientReturning(
            200,
            latestReleaseJson(
                assets = listOf(
                    asset("mycelium-v0.4.0-rc1.apk", "https://example.com/rc1.apk"),
                    asset("mycelium-v0.4.0-rc1.apk.sha256", "https://example.com/rc1.apk.sha256"),
                    asset("mycelium-v0.4.1.apk", "https://example.com/v041.apk"),
                    asset("mycelium-android-0.4.0.apk", "https://example.com/other-naming.apk"),
                ),
            ),
        )

        val release = client.latestRelease()!!

        assertNull(release.apkUrl)
        assertNull(release.sha256Url)
        assertEquals("0.4.0", release.versionName)
    }

    @Test
    fun releaseWithoutAnyAssetsStillReportsTheVersion() {
        val client = clientReturning(200, latestReleaseJson(assets = emptyList()))

        val release = client.latestRelease()!!

        assertEquals("0.4.0", release.versionName)
        assertEquals("https://github.com/plnoble/Mycelium-Android/releases/tag/v0.4.0", release.releasePageUrl)
        assertNull(release.apkUrl)
        assertNull(release.sha256Url)
        assertNull(release.apkSizeBytes)
    }

    @Test
    fun rateLimitIsReportedAsRateLimited() {
        for (status in listOf(403, 429)) {
            val failure = assertThrows(UpdateCheckException::class.java) {
                clientReturning(status, "").latestRelease()
            }

            assertEquals(UpdateError.RateLimited, failure.error)
            // 原因类型里不留英文原文，界面看不到 `GitHub returned HTTP 403`。
            assertNull(failure.message)
        }
    }

    @Test
    fun serverErrorIsReportedWithStatus() {
        for (status in listOf(500, 503)) {
            val failure = assertThrows(UpdateCheckException::class.java) {
                clientReturning(status, "").latestRelease()
            }

            assertEquals(UpdateError.Server(status), failure.error)
        }
    }

    @Test
    fun notFoundIsNoPublishedRelease() {
        assertNull(clientReturning(404, "").latestRelease())
    }

    @Test
    fun malformedJsonIsReportedAsMalformed() {
        for (body in listOf("not json", "{}", """{"html_url":"https://example.com","assets":[]}""", """{"tag_name":"v0.4.0"}""")) {
            val failure = assertThrows(UpdateCheckException::class.java) {
                clientReturning(200, body).latestRelease()
            }

            assertEquals("返回体：$body", UpdateError.Malformed, failure.error)
        }
    }

    @Test
    fun absentOptionalFieldsBecomeEmptyNotesAndNullTime() {
        val client = clientReturning(
            200,
            """{"tag_name":"v0.4.0","html_url":"https://example.com/release","body":null,"assets":[]}""",
        )

        val release = client.latestRelease()!!

        // body 是 JSON null 时不能变成字符串 "null"（Android 与独立 org.json 的 optString 行为不同）。
        assertEquals("", release.releaseNotes)
        assertNull(release.publishedAt)
    }

    @Test
    fun networkFailurePropagatesFromTheClient() {
        val failure = assertThrows(IOException::class.java) {
            GitHubReleaseClient { _, _, _ -> throw IOException("Connection timed out") }.latestRelease()
        }

        // 传输层异常照原样抛给 UpdateManager，由它收敛成 UpdateError.Network。
        assertTrue(failure.message!!.contains("timed out"))
    }

    @Test
    fun requestUsesTheDocumentedEndpointAndHeaders() {
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

    private fun clientReturning(status: Int, body: String): GitHubReleaseClient =
        GitHubReleaseClient { _, _, _ -> HttpText(status, body) }

    private fun latestReleaseJson(
        tag: String = "v0.4.0",
        assets: List<String>,
        body: String = RELEASE_NOTES,
        publishedAt: String = PUBLISHED_AT,
    ): String = """
        {
          "tag_name": "$tag",
          "html_url": "https://github.com/plnoble/Mycelium-Android/releases/tag/$tag",
          "published_at": "$publishedAt",
          "body": "$body",
          "draft": false,
          "prerelease": false,
          "assets": [${assets.joinToString(",")}]
        }
    """.trimIndent()

    private fun asset(name: String, url: String, size: Long = 1_000L): String =
        """{"name": "$name", "size": $size, "browser_download_url": "$url", "content_type": "application/octet-stream"}"""

    private companion object {
        const val APK_SIZE = 23_004_794L
        const val RELEASE_NOTES = "本版说明：圆桌、炉边、应用内更新。"
        const val PUBLISHED_AT = "2026-10-18T10:00:00Z"
        const val APK_URL = "https://github.com/plnoble/Mycelium-Android/releases/download/v0.4.0/mycelium-v0.4.0.apk"
        const val CHECKSUM_URL = "$APK_URL.sha256"
    }
}
