package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.net.GatewayCall
import com.xavierxia.mycelium.net.GatewayCancelled
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * M2：「下载并安装」必须真的两次点击就完事——下载/校验成功之后自己接着装，
 * 不再要求用户在界面上再点一次「安装」。
 *
 * 两例分别钉住两条分支：已授权 → 一次 `startDownload()` 就建一次会话；
 * 没授权 → 先落到「安装未知应用」说明，从设置页回来（回前台）接着装，同样不用再点一次。
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCenterAutoInstallTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prefs: UpdatePreferences

    @Before
    fun setUp() {
        prefs = UpdatePreferences(RuntimeEnvironment.getApplication())
    }

    private fun center(installer: InstallSessionGateway): UpdateCenter = testCenter(
        source = CountingReleaseSource { testRelease("0.5.0") },
        prefs = prefs,
        clock = { 100 * 3_600_000L },
        files = UpdateFiles(tmp.root, availableBytes = { Long.MAX_VALUE / 4 }),
        downloader = RecordingDownloader(),
        verifier = RecordingVerifier(),
        installer = installer,
    )

    @Test
    fun aVerifiedDownloadGoesStraightToInstallWithoutASecondTap() {
        val installer = RecordingInstaller()
        val center = center(installer)
        runBlocking { center.checkIfDue() }

        center.startDownload()

        assertEquals("一次 startDownload 就该建好会话，不该等第二次点击", 1, installer.installs)
        assertEquals("装的就是刚校验通过的那一版", "v0.5.0", installer.lastTag)
    }

    @Test
    fun withoutThePermissionTheNoticeComesFirstAndComingBackInstalls() {
        val installer = RecordingInstaller(canInstall = false)
        val center = center(installer)
        runBlocking { center.checkIfDue() }

        center.startDownload()

        assertEquals("没授权就不该建会话", 0, installer.installs)
        assertTrue(
            "没授权时应停在「安装未知应用」说明上",
            center.state.value.download is DownloadUiState.NeedsUnknownSources,
        )

        // 用户去设置里允许了「安装未知应用」，回到 App：前台接着装，不需要再点一次。
        installer.canInstall = true
        center.onResume()

        assertEquals("回前台后应当直接建会话", 1, installer.installs)
    }

    @Test
    fun whenTheSpaceDropsBeforeInstallingNoSessionIsCreated() {
        var free = Long.MAX_VALUE / 4
        val store = UpdateFiles(tmp.root, availableBytes = { free })
        val installer = RecordingInstaller()
        val center = testCenter(
            source = CountingReleaseSource { testRelease("0.5.0") },
            prefs = prefs,
            clock = { 100 * 3_600_000L },
            files = store,
            downloader = RecordingDownloader(),
            verifier = object : ApkVerifier {
                override val isAvailable: Boolean = true
                override suspend fun verify(apk: java.io.File, release: ReleaseInfo, call: GatewayCall): VerifyOutcome {
                    // 校验之后、安装之前，空间被别的应用吃掉了（M4 的复查要拦住这种）。
                    free = 0L
                    return VerifyOutcome.Verified(5L)
                }
            },
            installer = installer,
        )
        runBlocking { center.checkIfDue() }

        center.startDownload()

        assertEquals("空间不够就不许建会话", 0, installer.installs)
        assertTrue("要给明确的失败", center.state.value.download is DownloadUiState.Failed)
    }

    @Test
    fun aCancelledVerificationNeverReachesTheInstaller() {
        val installer = RecordingInstaller()
        val files = UpdateFiles(tmp.root, availableBytes = { Long.MAX_VALUE / 4 })
        val center = testCenter(
            source = CountingReleaseSource { testRelease("0.5.0") },
            prefs = prefs,
            clock = { 100 * 3_600_000L },
            files = files,
            downloader = RecordingDownloader(),
            verifier = object : ApkVerifier {
                override val isAvailable: Boolean = true
                override suspend fun verify(apk: java.io.File, release: ReleaseInfo, call: GatewayCall): VerifyOutcome {
                    call.cancel()
                    throw GatewayCancelled("stopped by test")
                }
            },
            installer = installer,
        )
        runBlocking { center.checkIfDue() }

        center.startDownload()

        assertEquals("校验阶段取消不许进安装", 0, installer.installs)
        assertEquals(DownloadUiState.Idle, center.state.value.download)
    }
}
