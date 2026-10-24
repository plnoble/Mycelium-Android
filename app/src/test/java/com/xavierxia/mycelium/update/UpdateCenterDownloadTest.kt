package com.xavierxia.mycelium.update

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.xavierxia.mycelium.net.GatewayCancelled
import com.xavierxia.mycelium.net.GatewayHttpError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 整条链路的落点：真实编排器（`GatewayApkDownloader` + 假传输层）+ 第 3 步的显式 fake，
 * 验证「空间不足不下载」「校验失败不留文件也不安装」「取校验和被取消不算失败」三件事。
 * 用 Robolectric 只是因为 `UpdatePreferences` 要 Context。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateCenterDownloadTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val mib = 1024L * 1024
    private val expected = "a".repeat(64)
    private val hour = 60L * 60 * 1000
    private val transport = FakeUpdateTransport()

    private lateinit var context: Context
    private lateinit var prefs: UpdatePreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = UpdatePreferences(context)
    }

    private fun center(
        free: Long,
        verifier: ApkVerifier = RecordingVerifier(),
        installer: InstallSessionGateway = RecordingInstaller(),
        files: UpdateFiles = UpdateFiles(tmp.root, availableBytes = { free }),
        clock: () -> Long = { 100 * hour },
    ): UpdateCenter = testCenter(
        source = CountingReleaseSource { testRelease("0.6.0") },
        prefs = prefs,
        clock = clock,
        files = files,
        // 这一组测的是状态机，不是线程边界：IO 边界换回 Unconfined，让 startDownload() 同步跑完。
        // 「真的在 IO 线程上」由 UpdateThreadBoundaryTest 用真实 dispatcher 记录线程来钉。
        downloader = GatewayApkDownloader(files, transport, io = Dispatchers.Unconfined),
        verifier = verifier,
        installer = installer,
    )

    private fun start(center: UpdateCenter) {
        runBlocking { center.checkIfDue() }
        center.startDownload()
    }

    @Test
    fun spaceShortageShowsTheDifferenceAndNeverDownloads() {
        transport.checksumText = expected
        val installer = RecordingInstaller()
        val center = center(free = 8 * mib, installer = installer)

        start(center)

        val failed = center.state.value.download as DownloadUiState.Failed
        assertEquals(DownloadFailureReason.Storage, failed.reason)
        assertTrue(failed.message.orEmpty(), failed.message.orEmpty().contains("还差"))
        // 没发起下载：一个字节都没写，也没有安装。
        assertEquals(0, transport.downloads)
        assertEquals(0, installer.installs)
    }

    @Test
    fun aVerificationFailureKeepsTheItemTextAndDeletesTheDownloadedFile() {
        transport.checksumText = expected
        val installer = RecordingInstaller()
        val files = UpdateFiles(tmp.root, availableBytes = { Long.MAX_VALUE / 4 })
        val center = center(
            free = Long.MAX_VALUE / 4,
            verifier = RecordingVerifier(VerifyOutcome.Failed("包名不对：这个包是 com.example.other。已删除。")),
            installer = installer,
            files = files,
        )

        start(center)

        val failed = center.state.value.download as DownloadUiState.Failed
        assertEquals(DownloadFailureReason.Verification, failed.reason)
        assertTrue(failed.message.orEmpty(), failed.message.orEmpty().contains("包名不对"))
        // 校验不过：文件删掉，绝不安装。
        assertFalse(files.apkFile("v0.6.0").exists())
        assertEquals(0, installer.installs)
    }

    @Test
    fun aCancelledChecksumFetchGoesBackToIdleInsteadOfShowingAnError() {
        transport.checksumFailure = GatewayCancelled("请求已被取消")
        val center = center(free = Long.MAX_VALUE / 4)

        start(center)

        assertEquals(DownloadUiState.Idle, center.state.value.download)
        assertEquals(0, transport.downloads)
    }

    @Test
    fun aChecksumFetchFailureIsReportedAsNetworkNotAsStorage() {
        transport.checksumFailure = GatewayHttpError(404, "元数据地址返回了 HTTP 404")
        val center = center(free = Long.MAX_VALUE / 4)

        start(center)

        val failed = center.state.value.download as DownloadUiState.Failed
        assertEquals(DownloadFailureReason.Network, failed.reason)
        assertEquals(0, transport.downloads)
    }

    @Test
    fun startupPurgeDeletesExpiredFiles() {
        // 「现在」用真实时钟：清理比的是文件年龄，测试里不能拿一个很小的 now 去比（会得到负时间）。
        val now = System.currentTimeMillis()
        val expired = File(tmp.root, "mycelium-v0.4.0.apk").apply { writeText("old") }
        expired.setLastModified(now - 8 * 24 * hour)

        center(free = Long.MAX_VALUE / 4, clock = { now })

        assertFalse(expired.exists())
    }

    @Test
    fun startupPurgeKeepsThePendingPartialFileSoResumeStillWorks() {
        val now = System.currentTimeMillis()
        // 候选发布落盘（进程重建后的常态），它的 .part 明明过期也必须留着。
        prefs.pendingReleaseTag = "v0.6.0"
        prefs.pendingReleaseJson = ReleaseJson.encode(testRelease("0.6.0"))
        val pending = File(tmp.root, "mycelium-v0.6.0.apk.part").apply { writeText("resume") }
        pending.setLastModified(now - 8 * 24 * hour)

        center(free = Long.MAX_VALUE / 4, clock = { now })

        assertTrue(pending.exists())
    }

    @Test
    fun aSuccessfulPipelinePromotesTheFileAndGoesStraightToInstall() {
        val apk = apkWithSidecar(tmp.root, "v0.6.0", "real-apk")
        transport.checksumText = sha256Of(apk)
        transport.payload = "real-apk".toByteArray()
        val files = UpdateFiles(tmp.root, availableBytes = { Long.MAX_VALUE / 4 })
        // 真机是「commit 后交给系统确认」，所以用 Committed：文件与待安装路径都要留着等确认。
        val installer = RecordingInstaller(InstallOutcome.Committed(1))
        val center = center(free = Long.MAX_VALUE / 4, files = files, installer = installer)

        start(center)

        // M2 之后这里不再停在 Ready：四项校验一过就自己接着装，用户不用再点第二次。
        assertEquals(1, installer.installs)
        assertEquals(files.finalFile("v0.6.0"), installer.lastApk)
        assertTrue(files.finalFile("v0.6.0").isFile)
        assertFalse(files.partFile("v0.6.0").exists())
        assertEquals(prefs.pendingApkPath, files.finalFile("v0.6.0").path)
    }
}