package com.xavierxia.mycelium.update

import android.content.Context
import com.xavierxia.mycelium.net.DownloadOutcome
import com.xavierxia.mycelium.net.DownloadProgress
import com.xavierxia.mycelium.net.DownloadResult
import com.xavierxia.mycelium.net.GatewayCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
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
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * M3：取消、忽略与「任务身份」。
 *
 * 三件必须同时成立，缺一条就会出真问题：
 *  * 取消要真的停掉那次任务（同一个取消句柄），不是只把界面改回 Idle；
 *  * 忽略一个版本要连带取消任务并清掉它的缓存，而不是留个孤儿任务继续跑、继续写文件；
 *  * 旧任务**结束得比新状态晚**时，它的进度与终态一律作废——否则「取消后立刻重下」会被上一轮盖回 Ready。
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCenterCancelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var prefs: UpdatePreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = UpdatePreferences(context)
    }

    /** 下到一半就挂起，等测例放行；放行后照常返回 Completed（模拟「旧任务恢复得太晚」）。 */
    private class GatedDownloader : ApkDownloader {
        override val isAvailable: Boolean = true
        val gate = CompletableDeferred<Unit>()
        val callRef = AtomicReference<GatewayCall?>(null)

        override suspend fun fetchExpectedSha256(release: ReleaseInfo, call: GatewayCall): String {
            callRef.set(call)
            return "a".repeat(64)
        }

        override suspend fun downloadApk(
            release: ReleaseInfo,
            target: File,
            expectedSha256: String,
            call: GatewayCall,
            onProgress: DownloadProgress?,
        ): DownloadOutcome {
            callRef.set(call)
            target.parentFile?.mkdirs()
            target.writeBytes(ByteArray(8))
            onProgress?.onProgress(8, 8)
            gate.await()
            return DownloadOutcome.Completed(
                DownloadResult(
                    status = 200,
                    finalUrl = release.apkUrl.orEmpty(),
                    hosts = listOf("github.com"),
                    bytesWritten = 8,
                    fileSize = 8,
                    redirects = 0,
                    restartedFromZero = false,
                ),
                expectedSha256,
            )
        }
    }

    private fun files(free: Long = Long.MAX_VALUE / 4): UpdateFiles =
        UpdateFiles(tmp.root, availableBytes = { free })

    private fun center(
        downloader: ApkDownloader,
        files: UpdateFiles,
        verifier: ApkVerifier = RecordingVerifier(),
    ): UpdateCenter = testCenter(
        source = CountingReleaseSource { testRelease("0.5.0") },
        prefs = prefs,
        clock = { 100 * 3_600_000L },
        files = files,
        downloader = downloader,
        verifier = verifier,
        installer = RecordingInstaller(),
    )

    private fun started(center: UpdateCenter) {
        runBlocking { center.checkIfDue() }
        center.startDownload()
    }

    @Test
    fun cancellingStopsTheTaskAndALateCompletionCannotBecomeReady() {
        val downloader = GatedDownloader()
        val verifier = RecordingVerifier()
        val store = files()
        val center = center(downloader, store, verifier)
        started(center)
        assertTrue("应当已经在下", center.state.value.download is DownloadUiState.Running)

        center.cancelDownload()
        assertEquals(DownloadUiState.Idle, center.state.value.download)
        assertTrue("取消必须落到同一个句柄上", downloader.callRef.get()!!.isCancelled)

        // 旧任务现在才恢复并按 Completed 返回：它已经不属于当前任务，不许再写状态。
        downloader.gate.complete(Unit)
        assertEquals("取消后不许再变 Ready", DownloadUiState.Idle, center.state.value.download)
        assertEquals("取消后不许再进校验", 0, verifier.verifications)
    }

    @Test
    fun ignoringTheVersionCancelsTheTaskAndDeletesItsCache() {
        val downloader = GatedDownloader()
        val store = files()
        val center = center(downloader, store)
        started(center)
        val tag = center.state.value.release!!.tagName
        val part = store.partFile(tag)
        val checksum = store.checksumFile(tag)
        part.writeBytes(ByteArray(4))
        checksum.writeText("a".repeat(64))
        assertTrue(part.isFile)

        center.ignore(tag)

        assertEquals(DownloadUiState.Idle, center.state.value.download)
        assertEquals(null, center.state.value.release)
        assertTrue("忽略也要取消任务", downloader.callRef.get()!!.isCancelled)
        // M3：此刻旧任务还在写，缓存**不能**已经删掉（删了就是删到它正在写的字节）。
        assertTrue("旧任务还在写时不许删缓存", part.exists())

        downloader.gate.complete(Unit)
        assertFalse("旧任务退出之后才删缓存", part.exists())
        assertFalse("边车一并删掉", checksum.exists())
        assertEquals("被忽略的版本不许再变 Ready", DownloadUiState.Idle, center.state.value.download)
    }

    @Test
    fun theVerifierSeesTheSameCancelHandleAsTheDownload() {
        val downloader = GatedDownloader()
        val verifier = RecordingVerifier()
        val center = center(downloader, files(), verifier)
        started(center)
        val downloadCall = downloader.callRef.get()
        downloader.gate.complete(Unit)
        assertEquals(1, verifier.verifications)
        assertTrue("校验必须拿到下载那一个句柄，而不是新开一个", downloadCall === verifier.lastCall)
    }
}
