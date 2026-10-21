package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.net.DownloadOutcome
import com.xavierxia.mycelium.net.GatewayCall
import com.xavierxia.mycelium.net.GatewayCancelled
import com.xavierxia.mycelium.net.GatewayRejected
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 编排层：**空间 → 下到 `.part`（带期望哈希与取消句柄）→ 长度复查 → promote**。
 * 全部 JVM：可用空间注入、传输层是显式 fake、真机 `StatFs`/`PackageManager` 不在这一层。
 */
class ApkDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val mib = 1024L * 1024
    private val expected = "a".repeat(64)
    private val release = testRelease("0.5.0")
    private val transport = FakeUpdateTransport()

    private fun downloader(free: Long, inUse: (File) -> Boolean = { false }) =
        GatewayApkDownloader(UpdateFiles(tmp.root, availableBytes = { free }), transport, inUse)

    private suspend fun download(free: Long) = downloader(free).downloadApk(
        release = release,
        target = File(tmp.root, "mycelium-${release.tagName}.apk"),
        expectedSha256 = expected,
        call = GatewayCall(),
        onProgress = null,
    )

    // ---- 空间：不够就不发起下载（C4 第 3 条） ----

    @Test
    fun spaceShortageBlocksTheDownloadAndSaysHowMuchIsMissing() {
        transport.checksumText = expected

        val lack = assertThrows(SpaceShortage::class.java) {
            runBlocking { download(free = 8 * mib) }
        }

        assertEquals(0, transport.downloads)
        assertTrue(lack.message.orEmpty(), lack.message.orEmpty().contains("还差"))
        assertTrue(lack.message.orEmpty(), lack.message.orEmpty().contains("可用只有"))
    }

    @Test
    fun exactlyEnoughSpaceIsAllowed() {
        transport.checksumText = expected
        val needed = UpdateFiles(tmp.root, availableBytes = { 0 }).requiredBytes(release.apkSizeBytes)

        val outcome = runBlocking { download(free = needed) }

        assertTrue(outcome is DownloadOutcome.Completed)
        assertEquals(1, transport.downloads)
    }

    @Test
    fun seventyTwoMiBCaseIsRefusedBeforeAnyDownload() {
        val big = release.copy(apkSizeBytes = 72 * mib)

        assertThrows(SpaceShortage::class.java) {
            runBlocking {
                downloader(free = 100 * mib).downloadApk(big, File(tmp.root, "b.apk"), expected, GatewayCall(), null)
            }
        }

        assertEquals(0, transport.downloads)
    }

    @Test
    fun unknownSizeUsesTheSixtyFourMiBBFloor() {
        val unknown = release.copy(apkSizeBytes = null)

        // 64 MiB 门槛刚好够：照常下载。
        val ok = runBlocking {
            downloader(free = 64 * mib).downloadApk(unknown, File(tmp.root, "u.apk"), expected, GatewayCall(), null)
        }
        assertTrue(ok is DownloadOutcome.Completed)

        // 差一个字节就拦下，而且一个字节都没下。
        val downloadsBefore = transport.downloads
        assertThrows(SpaceShortage::class.java) {
            runBlocking {
                downloader(free = 64 * mib - 1)
                    .downloadApk(unknown, File(tmp.root, "u2.apk"), expected, GatewayCall(), null)
            }
        }
        assertEquals(downloadsBefore, transport.downloads)
    }

    @Test
    fun aSpaceChangeAfterTheRealLengthArrivesStopsTheDownload() {
        // 开始以为大小未知（只过 64 MiB 门槛），进度里第一次报出 72 MiB 时再查一次。
        val unknown = release.copy(apkSizeBytes = null)
        val call = GatewayCall()
        transport.script = { _, target, progress ->
            target.parentFile?.mkdirs()
            target.writeBytes(ByteArray(16))
            progress?.onProgress(1 * mib, 72 * mib)
            if (call.isCancelled) DownloadOutcome.Cancelled else completed(target)
        }

        val lack = assertThrows(SpaceShortage::class.java) {
            runBlocking {
                downloader(free = 100 * mib)
                    .downloadApk(unknown, File(tmp.root, "r.apk"), expected, call, null)
            }
        }

        assertTrue(call.isCancelled)
        assertTrue(lack.message.orEmpty(), lack.message.orEmpty().contains("还差"))
        assertEquals(1, transport.downloads)
    }

    // ---- 期望哈希与取消句柄的接线（C4 第 1 条） ----

    @Test
    fun theExpectedHashIsTheOneHandedToTheDownloader() {
        runBlocking { download(free = Long.MAX_VALUE / 4) }

        assertEquals(expected, transport.lastExpected)
        assertEquals(release.apkUrl, transport.lastUrl)
    }

    @Test
    fun checksumFetchReturnsTheFirstHashFromTheSidecarText() {
        transport.checksumText = "${"b".repeat(64)}  mycelium-${release.tagName}.apk\n"

        val hash = runBlocking { downloader(0).fetchExpectedSha256(release, GatewayCall()) }

        assertEquals("b".repeat(64), hash)
        assertEquals(1, transport.checksumCalls)
    }

    @Test
    fun cancelledChecksumFetchPropagatesInsteadOfLookingLikeANetworkFailure() {
        transport.checksumFailure = GatewayCancelled("请求已被取消")

        assertThrows(GatewayCancelled::class.java) {
            runBlocking { downloader(0).fetchExpectedSha256(release, GatewayCall()) }
        }
    }

    @Test
    fun oversizedChecksumTextIsTreatedAsNoExpectedHash() {
        transport.checksumFailure = GatewayRejected("响应超过 4096 字节的上限")

        assertNull(runBlocking { downloader(0).fetchExpectedSha256(release, GatewayCall()) })
    }

    @Test
    fun checksumTextWithoutAHashIsTreatedAsNoExpectedHash() {
        transport.checksumText = "not a hash at all"

        assertNull(runBlocking { downloader(0).fetchExpectedSha256(release, GatewayCall()) })
    }

    // ---- `.part` / 取消 / promote ----

    @Test
    fun aCancelledDownloadKeepsThePartFileForTheNextResume() {
        transport.script = { _, target, _ ->
            target.parentFile?.mkdirs()
            target.writeBytes(ByteArray(5))
            DownloadOutcome.Cancelled
        }

        val outcome = runBlocking { download(free = Long.MAX_VALUE / 4) }

        assertEquals(DownloadOutcome.Cancelled, outcome)
        val part = File(tmp.root, "mycelium-${release.tagName}.apk.part")
        assertTrue(part.isFile)
        assertEquals(5L, part.length())
        assertFalse(File(tmp.root, "mycelium-${release.tagName}.apk").exists())
    }

    @Test
    fun aResumeHandsTheExistingPartBytesToTheTransport() {
        val part = File(tmp.root, "mycelium-${release.tagName}.apk.part").apply { writeText("12345") }
        var lengthSeenByTransport = -1L
        transport.script = { _, target, _ ->
            lengthSeenByTransport = target.length()
            DownloadOutcome.Cancelled
        }

        runBlocking { download(free = Long.MAX_VALUE / 4) }

        assertEquals(part, transport.lastTarget)
        assertEquals(5L, lengthSeenByTransport)
    }

    @Test
    fun aCompletedDownloadPromotesToTheFinalNameAndRecordsTheExpectedHash() {
        transport.payload = "the whole apk".toByteArray()

        val outcome = runBlocking { download(free = Long.MAX_VALUE / 4) }

        assertTrue(outcome is DownloadOutcome.Completed)
        val final = File(tmp.root, "mycelium-${release.tagName}.apk")
        assertTrue(final.isFile)
        assertEquals("the whole apk", final.readText())
        assertFalse(File(tmp.root, "mycelium-${release.tagName}.apk.part").exists())
        // 校验器读它，不再联网（C4 第 2 条）。
        assertEquals(expected, File(tmp.root, "mycelium-${release.tagName}.apk.sha256").readText())
        val completed = outcome as DownloadOutcome.Completed
        assertEquals(final.length(), completed.result.fileSize)
    }

    @Test
    fun aFailedDownloadLeavesNothingToPromote() {
        transport.script = { _, _, _ -> DownloadOutcome.Failed("连接被重置") }

        val outcome = runBlocking { download(free = Long.MAX_VALUE / 4) }

        assertTrue(outcome is DownloadOutcome.Failed)
        assertFalse(File(tmp.root, "mycelium-${release.tagName}.apk").exists())
    }

    @Test
    fun theAppAddsNoSecondRetryLayer() {
        // 重试与续传只在 `:net` 的 FileDownloader 里；这一层一次调用就结束（C4 第 1 条）。
        transport.script = { _, _, _ -> DownloadOutcome.ChecksumMismatch(expected, "c".repeat(64)) }

        val outcome = runBlocking { download(free = Long.MAX_VALUE / 4) }

        assertTrue(outcome is DownloadOutcome.ChecksumMismatch)
        assertEquals(1, transport.downloads)
    }

    @Test
    fun aMissingApkAssetIsRejectedWithoutTouchingTheNetwork() {
        val notInstallable = testRelease("0.5.0").copy(apkUrl = null)

        val outcome = runBlocking {
            downloader(Long.MAX_VALUE / 4)
                .downloadApk(notInstallable, File(tmp.root, "x.apk"), expected, GatewayCall(), null)
        }

        assertTrue(outcome is DownloadOutcome.Rejected)
        assertEquals(0, transport.downloads)
    }

    @Test
    fun aForeignTagPartFileIsDeletedWhenANewTagStarts() {
        val foreign = File(tmp.root, "mycelium-v0.4.0.apk.part").apply { writeText("old") }
        transport.script = { _, target, _ ->
            target.parentFile?.mkdirs()
            target.writeBytes(ByteArray(3))
            DownloadOutcome.Cancelled
        }

        runBlocking { download(free = Long.MAX_VALUE / 4) }

        assertFalse(foreign.exists())
        assertTrue(File(tmp.root, "mycelium-${release.tagName}.apk.part").exists())
    }

    @Test
    fun cleanupNeverDeletesAFileInUse() {
        val busy = File(tmp.root, "mycelium-v0.4.0.apk.part").apply { writeText("busy") }
        transport.script = { _, target, _ ->
            target.parentFile?.mkdirs()
            target.writeBytes(ByteArray(3))
            DownloadOutcome.Cancelled
        }

        runBlocking { downloader(Long.MAX_VALUE / 4, inUse = { it == busy }).downloadApk(
            release, File(tmp.root, "mycelium-${release.tagName}.apk"), expected, GatewayCall(), null,
        ) }

        assertTrue(busy.exists())
    }

    @Test
    fun progressIsPassedThroughToTheCaller() {
        val seen = mutableListOf<Pair<Long, Long?>>()
        transport.payload = ByteArray(64)

        runBlocking {
            downloader(Long.MAX_VALUE / 4).downloadApk(
                release,
                File(tmp.root, "mycelium-${release.tagName}.apk"),
                expected,
                GatewayCall(),
            ) { written, total -> seen += written to total }
        }

        assertEquals(listOf(64L to 64L), seen)
    }

    @Test
    fun promoteIsNotCalledForAnIncompleteOutcome() {
        transport.script = { _, target, _ ->
            target.parentFile?.mkdirs()
            target.writeBytes(ByteArray(7))
            DownloadOutcome.Rejected("跳转到了白名单外")
        }

        val outcome = runBlocking { download(free = Long.MAX_VALUE / 4) }

        assertTrue(outcome is DownloadOutcome.Rejected)
        assertFalse(File(tmp.root, "mycelium-${release.tagName}.apk").exists())
        assertNotNull(File(tmp.root, "mycelium-${release.tagName}.apk.part").exists())
    }
}