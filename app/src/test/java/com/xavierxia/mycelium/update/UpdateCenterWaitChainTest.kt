package com.xavierxia.mycelium.update

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
import java.util.Collections

/**
 * M3 等待链（第三轮复审的必修项）：被取消的旧任务必须留下句柄，后续的**重试**与**忽略**
 * 都要等它真正退出；下载中与校验中都不许再次进入。
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCenterWaitChainTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prefs: UpdatePreferences

    @Before
    fun setUp() {
        prefs = UpdatePreferences(RuntimeEnvironment.getApplication())
    }

    /** 记录进入/退出的顺序，用同一道闸门卡住每一次下载。 */
    private class SequencedDownloader : ApkDownloader {
        override val isAvailable: Boolean = true
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var gate = CompletableDeferred<Unit>()
        var downloads = 0

        override suspend fun fetchExpectedSha256(release: ReleaseInfo, call: GatewayCall): String = "a".repeat(64)

        override suspend fun downloadApk(
            release: ReleaseInfo,
            target: File,
            expectedSha256: String,
            call: GatewayCall,
            onProgress: DownloadProgress?,
        ): DownloadOutcome {
            downloads += 1
            val index = downloads
            events += "enter#$index"
            target.parentFile?.mkdirs()
            target.writeBytes(ByteArray(8))
            gate.await()
            events += "exit#$index"
            return DownloadOutcome.Completed(
                DownloadResult(200, release.apkUrl.orEmpty(), listOf("github.com"), 8, 8, 0, false),
                expectedSha256,
            )
        }
    }

    private fun files() = UpdateFiles(tmp.root, availableBytes = { Long.MAX_VALUE / 4 })

    private fun center(downloader: ApkDownloader, files: UpdateFiles, verifier: ApkVerifier = RecordingVerifier()): UpdateCenter =
        testCenter(
            source = CountingReleaseSource { testRelease("0.6.0") },
            prefs = prefs,
            clock = { 100 * 3_600_000L },
            files = files,
            downloader = downloader,
            verifier = verifier,
            installer = RecordingInstaller(canInstall = false),
        )

    @Test
    fun cancellingAndRetryingImmediatelyWaitsForTheOldTaskToLeave() {
        val downloader = SequencedDownloader()
        val center = center(downloader, files())
        runBlocking { center.checkIfDue() }
        center.startDownload()
        assertTrue("第一次下载应已进入", downloader.events.contains("enter#1"))

        center.cancelDownload()
        center.startDownload() // 取消后立刻重试

        downloader.gate.complete(Unit)
        runBlocking { kotlinx.coroutines.delay(300) }

        // 关键：两次下载不能重叠——第二次必须等第一次 exit 之后才 enter。
        val order = downloader.events.toList()
        assertTrue("事件顺序应体现等待：$order", order.indexOf("exit#1") < order.indexOf("enter#2"))
        assertEquals("顺序应为 enter#1 → exit#1 → enter#2 → exit#2", listOf("enter#1", "exit#1", "enter#2", "exit#2"), order)
    }

    @Test
    fun cancellingAndIgnoringImmediatelyDeletesOnlyAfterTheOldTaskLeaves() {
        val downloader = SequencedDownloader()
        val store = files()
        val center = center(downloader, store)
        runBlocking { center.checkIfDue() }
        center.startDownload()
        val tag = center.state.value.release!!.tagName
        val part = store.partFile(tag)
        part.writeBytes(ByteArray(4))

        center.cancelDownload()
        center.ignore(tag)
        assertTrue("旧任务还在写时不许删缓存", part.exists())

        downloader.gate.complete(Unit)
        runBlocking { kotlinx.coroutines.delay(300) }
        assertFalse("旧任务退出之后才删缓存", part.exists())
    }

    @Test
    fun aSecondStartWhileVerifyingIsRefused() {
        val downloader = SequencedDownloader()
        val gate = CompletableDeferred<Unit>()
        val verifier = object : ApkVerifier {
            override val isAvailable: Boolean = true
            override suspend fun verify(apk: File, release: ReleaseInfo, call: GatewayCall): VerifyOutcome {
                gate.await() // 一直卡在校验里
                return VerifyOutcome.Verified(5L)
            }
        }
        val center = center(downloader, files(), verifier)
        runBlocking { center.checkIfDue() }
        center.startDownload()
        downloader.gate.complete(Unit) // 放行下载，让它进入校验
        runBlocking { kotlinx.coroutines.delay(200) }
        assertTrue("应当已经进入校验", center.state.value.download is DownloadUiState.Verifying)

        center.startDownload() // 校验中重入：必须被拒绝

        assertEquals("校验中不许再起一次下载", 1, downloader.downloads)
        gate.complete(Unit)
        runBlocking { kotlinx.coroutines.delay(200) }
        assertEquals(1, downloader.downloads)
    }
}
