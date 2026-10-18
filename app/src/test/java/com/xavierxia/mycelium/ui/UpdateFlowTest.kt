package com.xavierxia.mycelium.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.xavierxia.mycelium.net.DownloadOutcome
import com.xavierxia.mycelium.net.DownloadProgress
import com.xavierxia.mycelium.net.GatewayCall
import com.xavierxia.mycelium.update.ApkDownloader
import com.xavierxia.mycelium.update.ApkVerifier
import com.xavierxia.mycelium.update.CountingReleaseSource
import com.xavierxia.mycelium.update.InstallOutcome
import com.xavierxia.mycelium.update.InstallSessionGateway
import com.xavierxia.mycelium.update.ReleaseInfo
import com.xavierxia.mycelium.update.UpdateCenter
import com.xavierxia.mycelium.update.UpdateError
import com.xavierxia.mycelium.update.UpdateCheckException
import com.xavierxia.mycelium.update.UpdateFormat
import com.xavierxia.mycelium.update.UpdatePreferences
import com.xavierxia.mycelium.update.VerifyOutcome
import com.xavierxia.mycelium.update.testCenter
import com.xavierxia.mycelium.update.testRelease
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 界面上「我的」里更新那一段的可观察行为。检查的调度在 `UpdateCenterTest` 里测，
 * 这里只看界面：红点、卡片、开关、「上次检查」、以及失败时**不**出现的文案。
 *
 * 下载/校验/安装用显式 fake（[WiredDownloader] 等），证明「接线后才画下载按钮」这条门控真的生效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class UpdateFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun aNewVersionShowsTheBadgeAndTheCard() {
        val source = CountingReleaseSource { testRelease("0.5.0") }
        val center = testCenter(source, UpdatePreferences(context), clock = { 1_000L })
        compose.setContent { MyceliumApp(center) }

        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("检查更新").performClick()
        awaitText("发现新版本：0.5.0")

        assertTrue(badgeNodes() > 0)
        compose.onNodeWithText("安装包 38.5 MiB").assertIsDisplayed()
        compose.onNodeWithText("修了几个问题").assertIsDisplayed()
        compose.onNodeWithText("忽略这个版本").assertIsDisplayed()
        compose.onNodeWithText("打开发布页").assertIsDisplayed()
        // 第 3 步的下载路径没接线：不放一个按了没反应的「下载并安装」。
        assertTrue(compose.onAllNodesWithText("下载并安装").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun ignoringTheVersionRemovesTheBadgeAndTheCard() {
        val source = CountingReleaseSource { testRelease("0.5.0") }
        val center = testCenter(source, UpdatePreferences(context), clock = { 1_000L })
        compose.setContent { MyceliumApp(center) }

        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("检查更新").performClick()
        awaitText("发现新版本：0.5.0")

        compose.onNodeWithText("忽略这个版本").performClick()
        awaitGone("发现新版本：0.5.0")

        assertEquals(0, badgeNodes())
    }

    @Test
    fun anUnknownSizeAndAMissingInstallableAssetAreSaidOutLoud() {
        val release = testRelease("0.5.0", installable = false).copy(apkSizeBytes = null)
        val source = CountingReleaseSource { release }
        val center = testCenter(source, UpdatePreferences(context), clock = { 1_000L })
        compose.setContent { MyceliumApp(center) }

        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("检查更新").performClick()
        awaitText("发现新版本：0.5.0")

        compose.onNodeWithText("大小未知").assertIsDisplayed()
        compose.onNodeWithText("这个版本没有可用的安装包，请到发布页查看。").assertIsDisplayed()
        compose.onNodeWithText("打开发布页").assertIsDisplayed()
    }

    @Test
    fun longReleaseNotesFoldAndUnfold() {
        val source = CountingReleaseSource { testRelease("0.5.0").copy(releaseNotes = "很长的一段说明。".repeat(30)) }
        val center = testCenter(source, UpdatePreferences(context), clock = { 1_000L })
        compose.setContent { MyceliumApp(center) }

        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("检查更新").performClick()
        awaitText("发现新版本：0.5.0")

        compose.onNodeWithText("展开更新说明").performClick()
        compose.onNodeWithText("收起更新说明").assertIsDisplayed()
    }

    @Test
    fun theInstallEntryAppearsOnlyWhenTheDownloadPathIsWiredAndTheBuildIsNotDebug() {
        val wired = CountingReleaseSource { testRelease("0.5.0") }
        val releaseCenter = testCenter(
            wired,
            UpdatePreferences(context),
            clock = { 1_000L },
            isDebugPackage = false,
            downloader = WiredDownloader(),
            verifier = WiredVerifier(),
            installer = WiredInstaller(),
        )
        compose.setContent { MyceliumApp(releaseCenter) }
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("检查更新").performClick()
        awaitText("下载并安装")

        assertTrue(compose.onAllNodesWithText("下载并安装").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aDebugPackageNeverOffersTheInstallEntry() {
        val source = CountingReleaseSource { testRelease("0.5.0") }
        val center = testCenter(
            source,
            UpdatePreferences(context),
            clock = { 1_000L },
            isDebugPackage = true,
            downloader = WiredDownloader(),
            verifier = WiredVerifier(),
            installer = WiredInstaller(),
        )
        compose.setContent { MyceliumApp(center) }

        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("检查更新").performClick()
        awaitText("发现新版本：0.5.0")

        assertTrue(compose.onAllNodesWithText("下载并安装").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aFailedAutomaticCheckShowsNothing() {
        val prefs = UpdatePreferences(context)
        prefs.autoCheck = true
        val source = CountingReleaseSource { throw IOException("Network unavailable") }
        val center = testCenter(source, prefs, clock = { 1_000L })
        compose.setContent { MyceliumApp(center) }

        compose.waitUntil(timeoutMillis = 5_000) { source.calls == 1 }
        compose.waitForIdle()

        assertTrue(compose.onAllNodesWithText("检查更新失败", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Network unavailable").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aManualRateLimitIsSaidInPlainChinese() {
        val source = CountingReleaseSource { throw UpdateCheckException(UpdateError.RateLimited) }
        val center = testCenter(source, UpdatePreferences(context), clock = { 1_000L })
        compose.setContent { MyceliumApp(center) }

        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("检查更新").performClick()
        awaitText("检查更新失败：检查太频繁，稍后再试")

        assertTrue(compose.onAllNodesWithText("403").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("GitHub returned", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theAutoCheckSwitchReflectsAndUpdatesTheStoredValue() {
        val prefs = UpdatePreferences(context)
        val center = testCenter(CountingReleaseSource { null }, prefs, clock = { 1_000L })
        compose.setContent { MyceliumApp(center) }

        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("自动检查更新").assertIsDisplayed()
        assertTrue(prefs.autoCheck)

        compose.onAllNodes(isToggleable())[0].performClick()

        assertFalse(prefs.autoCheck)
    }

    @Test
    fun theLastCheckLineShowsNeverCheckedThenTheSuccessTime() {
        val prefs = UpdatePreferences(context)
        // 关掉自动检查：这里只看「上次检查」这一行，卡片会把版面撑长、把这行挤出屏幕。
        prefs.autoCheck = false
        prefs.lastSuccessAtMs = 0L
        val source = CountingReleaseSource { null }
        val center = testCenter(source, prefs, clock = { 1_000L })
        compose.setContent { MyceliumApp(center) }

        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("还没检查过").assertIsDisplayed()

        compose.onNodeWithText("检查更新").performClick()
        // 显示层与格式化是同一处实现；格式本身在 UpdateFormatTest 里用固定时区锁死。
        awaitText("上次检查：${UpdateFormat.lastCheckAt(1_000L)}")
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun awaitGone(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun badgeNodes(): Int =
        compose.onAllNodesWithTag(UpdateBadgeTag, useUnmergedTree = true).fetchSemanticsNodes().size
}

/** 接线后的显式 fake：证明「三个接口都接线才画下载按钮」的门控。 */
private class WiredDownloader : ApkDownloader {
    override val isAvailable: Boolean = true

    override suspend fun fetchExpectedSha256(release: ReleaseInfo, call: GatewayCall): String? = "a".repeat(64)

    override suspend fun downloadApk(
        release: ReleaseInfo,
        target: File,
        expectedSha256: String,
        call: GatewayCall,
        onProgress: DownloadProgress?,
    ): DownloadOutcome = DownloadOutcome.Cancelled
}

private class WiredVerifier : ApkVerifier {
    override val isAvailable: Boolean = true

    override suspend fun verify(apk: File, release: ReleaseInfo): VerifyOutcome = VerifyOutcome.Verified
}

private class WiredInstaller : InstallSessionGateway {
    override val isAvailable: Boolean = true

    override fun canRequestPackageInstalls(): Boolean = true

    override fun unknownSourcesIntent(): Intent = Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES")

    override suspend fun install(apk: File, tagName: String, baselineVersionCode: Long): InstallOutcome =
        InstallOutcome.Cancelled

    override fun cancel() = Unit
}