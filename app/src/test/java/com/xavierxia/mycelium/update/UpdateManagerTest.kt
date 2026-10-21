package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.BuildConfig
import com.xavierxia.mycelium.net.HttpText
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** 检查更新的结果：错误一律以 [UpdateError] 类型交给界面，界面上不出现英文原文。 */
class UpdateManagerTest {
    @Test
    fun github404BecomesNoPublishedRelease() = runBlocking {
        val client = GitHubReleaseClient { _, _, _ -> HttpText(404, "") }

        assertEquals(UpdateResult.NoPublishedRelease, UpdateManager(client).checkForUpdate())
    }

    @Test
    fun newerReleaseIsAvailable() = runBlocking {
        val release = release("999.0.0")
        val manager = UpdateManager(ReleaseSource { release })

        assertEquals(UpdateResult.Available(release), manager.checkForUpdate())
    }

    @Test
    fun newerReleaseWithoutInstallableAssetsIsStillAvailable() = runBlocking {
        // 缺 .apk / .sha256 的发布仍然要提示「有新版本」，只是给不出应用内安装。
        val release = release("999.0.0", apkUrl = null, sha256Url = null, apkSizeBytes = null)
        val manager = UpdateManager(ReleaseSource { release })

        val result = manager.checkForUpdate() as UpdateResult.Available

        assertNull(result.release.apkUrl)
        assertNull(result.release.sha256Url)
    }

    @Test
    fun sameOrOlderReleaseIsUpToDate() = runBlocking {
        for (version in listOf(BuildConfig.VERSION_NAME, "0.0.0")) {
            val manager = UpdateManager(ReleaseSource { release(version) })
            assertEquals(UpdateResult.UpToDate, manager.checkForUpdate())
        }
    }

    @Test
    fun rateLimitBecomesRateLimitedError() = runBlocking {
        val client = GitHubReleaseClient { _, _, _ -> HttpText(403, "") }

        assertEquals(UpdateResult.Error(UpdateError.RateLimited), UpdateManager(client).checkForUpdate())
    }

    @Test
    fun serverFailureKeepsTheStatusCodeOutOfTheUserFacingMessage() = runBlocking {
        val client = GitHubReleaseClient { _, _, _ -> HttpText(500, "") }

        assertEquals(UpdateResult.Error(UpdateError.Server(500)), UpdateManager(client).checkForUpdate())
    }

    @Test
    fun malformedResponseBecomesMalformedError() = runBlocking {
        val client = GitHubReleaseClient { _, _, _ -> HttpText(200, "not json") }

        assertEquals(UpdateResult.Error(UpdateError.Malformed), UpdateManager(client).checkForUpdate())
    }

    @Test
    fun networkFailureBecomesNetworkError() = runBlocking {
        val manager = UpdateManager(ReleaseSource { throw IOException("Network unavailable") })

        assertEquals(UpdateResult.Error(UpdateError.Network), manager.checkForUpdate())
    }

    @Test
    fun unexpectedFailureIsReportedAsNetwork() = runBlocking {
        // 取数这一层其余的失败（网关拒绝、请求被取消等）对用户来说可做的动作也是稍后重试。
        val manager = UpdateManager(ReleaseSource { throw IllegalStateException("Gateway rejected") })

        assertEquals(UpdateResult.Error(UpdateError.Network), manager.checkForUpdate())
    }

    @Test
    fun cancellationIsPropagated() {
        val manager = UpdateManager(ReleaseSource { throw CancellationException("Screen closed") })

        assertThrows(CancellationException::class.java) {
            runBlocking { manager.checkForUpdate() }
        }
    }

    private fun release(
        version: String,
        apkUrl: String? = "https://example.com/mycelium-v$version.apk",
        sha256Url: String? = "https://example.com/mycelium-v$version.apk.sha256",
        apkSizeBytes: Long? = 23_004_794L,
    ) = ReleaseInfo(
        versionName = version,
        tagName = "v$version",
        releasePageUrl = "https://github.com/plnoble/Mycelium-Android/releases/tag/v$version",
        apkUrl = apkUrl,
        apkSizeBytes = apkSizeBytes,
        sha256Url = sha256Url,
        releaseNotes = "本版说明",
        publishedAt = "2026-10-18T10:00:00Z",
    )
}
