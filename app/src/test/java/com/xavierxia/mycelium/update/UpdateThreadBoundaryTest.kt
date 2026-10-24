package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.net.DownloadOutcome
import com.xavierxia.mycelium.net.DownloadProgress
import com.xavierxia.mycelium.net.DownloadResult
import com.xavierxia.mycelium.net.GatewayCall
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * M1：生产下载与校验必须跑在明确的 IO 线程上，而不是调用它的那个线程（真机是主线程）。
 *
 * 这几例用的都是**真实现**（真的 `GatewayApkDownloader`/`LocalApkVerifier`），只把
 * 「网络/读盘」这一层换成记录线程并可按需阻塞的 transport —— 同步 fake 证明不了线程，
 * 所以这里既断言线程名，也用「主线程在下载阻塞期间仍能立刻干活」来反证没有被堵住。
 */
class UpdateThreadBoundaryTest {
    private fun dispatcher(name: String): ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name) }.asCoroutineDispatcher()

    private class ThreadRecordingTransport(
        private val downloadGate: CountDownLatch? = null,
        private val downloadEntered: CountDownLatch? = null,
        private val fixedSha256: String = "a".repeat(64),
    ) : UpdateTransport {
        @Volatile var checksumThread: String? = null
        @Volatile var downloadThread: String? = null
        @Volatile var hashThread: String? = null

        /** 线程名，去掉 kotlinx 追加的 " @coroutine#N" 后缀：这里要判的是线程，不是协程。 */
        private fun here(): String = Thread.currentThread().name.substringBefore(" @")

        override fun fetchChecksumText(url: String, call: GatewayCall, limitBytes: Int): String {
            checksumThread = here()
            return fixedSha256
        }

        override fun downloadApk(
            url: String,
            target: File,
            expectedSha256: String,
            call: GatewayCall,
            progress: DownloadProgress?,
        ): DownloadOutcome {
            downloadThread = here()
            downloadEntered?.countDown()
            downloadGate?.await(5, TimeUnit.SECONDS)
            target.parentFile?.mkdirs()
            target.writeBytes(ByteArray(16))
            return DownloadOutcome.Completed(
                DownloadResult(
                    status = 200,
                    finalUrl = url,
                    hosts = listOf("example.test"),
                    bytesWritten = 16,
                    fileSize = 16,
                    redirects = 0,
                    restartedFromZero = false,
                ),
                expectedSha256,
            )
        }

        override fun sha256(file: File, call: GatewayCall): String {
            hashThread = here()
            return fixedSha256
        }
    }

    @Test(timeout = 30_000)
    fun theChecksumAndTheDownloadRunOnTheIoDispatcherNotOnTheCaller() = runBlocking {
        val main = dispatcher("m1-main")
        val io = dispatcher("m1-io")
        val root = Files.createTempDirectory("m1-download").toFile()
        val release = testRelease("0.6.0").copy(apkSizeBytes = 1024L)
        val transport = ThreadRecordingTransport()
        val downloader = GatewayApkDownloader(
            UpdateFiles(File(root, "files"), availableBytes = { Long.MAX_VALUE / 4 }),
            transport,
            io = io,
        )
        try {
            val expected = withContext(main) { downloader.fetchExpectedSha256(release, GatewayCall()) }
            assertEquals("a".repeat(64), expected)
            assertEquals("查 .sha256 必须在 IO 线程上", "m1-io", transport.checksumThread)

            val outcome = withContext(main) {
                downloader.downloadApk(release, File(root, "mycelium.apk"), expected!!, GatewayCall()) { _, _ -> }
            }
            assertTrue("下载应当成功", outcome is DownloadOutcome.Completed)
            assertEquals("下载必须在 IO 线程上", "m1-io", transport.downloadThread)
        } finally {
            io.close()
            main.close()
            root.deleteRecursively()
        }
    }

    @Test(timeout = 30_000)
    fun theHashAndTheArchiveReadRunOnTheIoDispatcher() = runBlocking {
        val main = dispatcher("m1-main")
        val io = dispatcher("m1-io")
        val root = Files.createTempDirectory("m1-verify").toFile()
        val apk = File(root, "mycelium-v0.6.0.apk")
        apk.writeBytes(ByteArray(32))
        File(root, apk.name + ".sha256").writeText("a".repeat(64) + "\n")
        val transport = ThreadRecordingTransport()
        var archiveThread: String? = null
        var installedThread: String? = null
        val verifier = LocalApkVerifier(
            transport = transport,
            archive = { _ ->
                archiveThread = Thread.currentThread().name.substringBefore(" @")
                ApkFacts("com.xavierxia.mycelium", 5L, "ab".repeat(32))
            },
            installed = {
                installedThread = Thread.currentThread().name.substringBefore(" @")
                ApkFacts("com.xavierxia.mycelium", 4L, "ab".repeat(32))
            },
            selfPackageName = "com.xavierxia.mycelium",
            io = io,
        )
        try {
            val verdict = withContext(main) { verifier.verify(apk, testRelease("0.6.0")) }
            assertTrue("校验应当通过", verdict is VerifyOutcome.Verified)
            assertEquals("算哈希必须在 IO 线程上", "m1-io", transport.hashThread)
            assertEquals("读 APK 必须在 IO 线程上", "m1-io", archiveThread)
            assertEquals("读已装版本也必须在 IO 线程上", "m1-io", installedThread)
        } finally {
            io.close()
            main.close()
            root.deleteRecursively()
        }
    }

    @Test(timeout = 30_000)
    fun theMainThreadStaysFreeWhileTheDownloadBlocks() = runBlocking {
        val main = dispatcher("m1-main")
        val io = dispatcher("m1-io")
        val root = Files.createTempDirectory("m1-block").toFile()
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val release = testRelease("0.6.0").copy(apkSizeBytes = 1024L)
        val transport = ThreadRecordingTransport(downloadGate = gate, downloadEntered = entered)
        val files = UpdateFiles(File(root, "files"), availableBytes = { Long.MAX_VALUE / 4 })
        val downloader = GatewayApkDownloader(files, transport, io = io)
        try {
            val job = launch(main) {
                downloader.downloadApk(release, files.apkFile(release.tagName), "a".repeat(64), GatewayCall()) { _, _ -> }
            }
            assertTrue("下载应已进入阻塞点", entered.await(5, TimeUnit.SECONDS))
            // 下载卡在 IO 线程上时，主线程应该仍然能立刻跑完一段自己的活；没切线程的话这里会超时。
            withTimeout(2_000) { withContext(main) { Unit } }
            assertEquals("m1-io", transport.downloadThread)
            gate.countDown()
            withTimeout(5_000) { job.join() }
        } finally {
            io.close()
            main.close()
            root.deleteRecursively()
        }
    }
}
