package com.xavierxia.mycelium.update

import com.sun.net.httpserver.HttpServer
import com.xavierxia.mycelium.net.DownloadOutcome
import com.xavierxia.mycelium.net.DownloadProgress
import com.xavierxia.mycelium.net.DownloadResult
import com.xavierxia.mycelium.net.GatewayCall
import com.xavierxia.mycelium.net.GatewayCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * M1 的组合证据：**真实的** `UpdateCenter` + `GatewayApkDownloader` + `LocalApkVerifier`
 * + 本机回环慢服务 + 真实哈希计算（没有固定返回值）。
 *
 * 三条断言，都按统筹 2026-10-20 的口径：
 *  1. 网络请求、读盘、哈希都不在「主线程」上执行（记录实际线程名比对）；
 *  2. 慢下载过程中取消能在有界时间内结束；
 *  3. 取消之后不会再发下一次请求（回环服务记数）。
 */
@RunWith(RobolectricTestRunner::class)
class UpdateRealTransportTest {
    private lateinit var server: HttpServer
    private val payload = ByteArray(512 * 1024) { (it % 251).toByte() }
    private val requests = AtomicInteger()
    private val threads = CopyOnWriteArrayList<String>()
    private val slowSeconds = AtomicInteger(0)

    private val digest: String
        get() = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/asset") { exchange ->
            requests.incrementAndGet()
            val body = payload
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { out ->
                var offset = 0
                while (offset < body.size) {
                    val length = minOf(16 * 1024, body.size - offset)
                    out.write(body, offset, length)
                    out.flush()
                    offset += length
                    val pause = slowSeconds.get()
                    if (pause > 0) Thread.sleep(pause.toLong())
                }
            }
        }
        server.createContext("/sha") { exchange ->
            requests.incrementAndGet()
            val text = (digest + "\n").toByteArray()
            exchange.sendResponseHeaders(200, text.size.toLong())
            exchange.responseBody.use { it.write(text) }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    /** 真实传输层：HttpURLConnection + 真实 SHA-256，只记录执行线程。 */
    private inner class RealTransport : UpdateTransport {
        private fun note() {
            threads += Thread.currentThread().name.substringBefore(" @")
        }

        override fun fetchChecksumText(url: String, call: GatewayCall, limitBytes: Int): String {
            note()
            val connection = URL(url).openConnection() as HttpURLConnection
            return try {
                stopCheck(call)
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                stopCheck(call)
                text
            } finally {
                connection.disconnect()
            }
        }

        override fun downloadApk(
            url: String,
            target: File,
            expectedSha256: String,
            call: GatewayCall,
            progress: DownloadProgress?,
        ): DownloadOutcome {
            note()
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 10_000
                val total = connection.contentLengthLong
                target.parentFile?.mkdirs()
                var written = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            stopCheck(call)
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            progress?.onProgress(written, total.takeIf { it > 0 })
                        }
                    }
                }
                val actual = sha256(target)
                return DownloadOutcome.Completed(
                    DownloadResult(200, url, listOf("127.0.0.1"), written, target.length(), 0, false),
                    actual,
                )
            } catch (cancelled: GatewayCancelled) {
                throw cancelled
            } finally {
                connection.disconnect()
            }
        }

        override fun sha256(file: File, call: GatewayCall): String {
            note()
            return sha256(file)
        }

        private fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }
    }

    /** GatewayCall.check() 是 :net 内部的；:app 侧只用公开的取消标志。 */
    private fun stopCheck(call: GatewayCall) {
        if (call.isCancelled) throw GatewayCancelled("stopped")
    }

    private fun mainThreadName(): String = "m1-main"

    private fun dispatcher(name: String): ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name) }.asCoroutineDispatcher()

    private fun center(main: ExecutorCoroutineDispatcher, files: UpdateFiles): UpdateCenter {
        val transport = RealTransport()
        val base = "http://127.0.0.1:${server.address.port}"
        val release = ReleaseInfo(
            versionName = "0.5.0",
            tagName = "v0.5.0",
            releasePageUrl = "$base/page",
            apkUrl = "$base/asset",
            apkSizeBytes = payload.size.toLong(),
            sha256Url = "$base/sha",
            releaseNotes = "notes",
            publishedAt = "2026-10-20T00:00:00Z",
        )
        val center = UpdateCenter(
            updates = UpdateManager(ReleaseSource { release }),
            prefs = UpdatePreferences(org.robolectric.RuntimeEnvironment.getApplication()),
            isDebugPackage = false,
            files = files,
            downloader = GatewayApkDownloader(files, transport),
            verifier = LocalApkVerifier(
                transport = transport,
                archive = { path -> ApkFacts("com.xavierxia.mycelium", 9L, "ab".repeat(32)) },
                installed = { ApkFacts("com.xavierxia.mycelium", 4L, "ab".repeat(32)) },
                selfPackageName = "com.xavierxia.mycelium",
            ),
            installer = RecordingInstaller(canInstall = false),
            clock = { 100 * 3_600_000L },
            installedVersionCode = { 4L },
            sessionState = MemoryInstallSessionState(),
            scope = CoroutineScope(main),
        )
        return center
    }

    @Test(timeout = 60_000)
    fun networkDiskAndHashAllRunOffTheMainThread() {
        val main = dispatcher(mainThreadName())
        val files = UpdateFiles(File(System.getProperty("java.io.tmpdir"), "m1-real-${System.nanoTime()}"), availableBytes = { Long.MAX_VALUE / 4 })
        val center = center(main, files)
        try {
            runBlocking {
                withContext(main) { center.checkIfDue() }
                withContext(main) { center.startDownload() }
                // 等它下完并校验（都在后台线程）
                withTimeout(30_000) {
                    while (center.state.value.download !is DownloadUiState.NeedsUnknownSources &&
                        center.state.value.download !is DownloadUiState.Failed
                    ) {
                        kotlinx.coroutines.delay(20)
                    }
                }
            }
            assertTrue("应当下载并校验完成（未授权会停在说明页）：${center.state.value.download}", center.state.value.download is DownloadUiState.NeedsUnknownSources)
            assertTrue("至少发生过网络/哈希调用", threads.isNotEmpty())
            assertTrue("网络与哈希不能跑在主线程上，实际线程：$threads", threads.none { it == mainThreadName() })
            assertEquals("取消前只该有一个 APK 请求 + 一个 .sha256 请求", 2, requests.get())
        } finally {
            main.close()
            files.directory().deleteRecursively()
        }
    }

    @Test(timeout = 60_000)
    fun aStopEndsASlowDownloadWithinBoundedTimeAndSendsNoFurtherRequest() {
        slowSeconds.set(20) // 每 16 KiB 停 20 ms：512 KiB 约 640 ms 起
        val main = dispatcher(mainThreadName())
        val files = UpdateFiles(File(System.getProperty("java.io.tmpdir"), "m1-slow-${System.nanoTime()}"), availableBytes = { Long.MAX_VALUE / 4 })
        val center = center(main, files)
        try {
            runBlocking {
                withContext(main) { center.checkIfDue() }
                withContext(main) { center.startDownload() }
                // 等 .sha256 与 APK 两个请求都真的发出去、状态在 Running，才是「取消落在下载中」。
                withTimeout(10_000) {
                    while (requests.get() < 2 || center.state.value.download !is DownloadUiState.Running) {
                        kotlinx.coroutines.delay(10)
                    }
                }
                val before = requests.get()
                val startedAt = System.nanoTime()
                withContext(main) { center.cancelDownload() }
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                assertTrue("取消必须在有界时间内返回，实际 ${elapsedMs}ms", elapsedMs < 2_000)
                assertTrue("取消后状态应回到 Idle：${center.state.value.download}", center.state.value.download is DownloadUiState.Idle)
                // 再等一会儿，确认没有迟到的进度把状态写回去，也没有新请求
                kotlinx.coroutines.delay(500)
                assertTrue("取消后不许再写回运行态：${center.state.value.download}", center.state.value.download is DownloadUiState.Idle)
                assertEquals("取消之后不该再发请求", before, requests.get())
            }
        } finally {
            main.close()
            files.directory().deleteRecursively()
        }
    }

    @Test(timeout = 30_000)
    fun theHashIsRealAndMatchesTheServedBytes() {
        val transport = RealTransport()
        val file = File(System.getProperty("java.io.tmpdir"), "m1-hash-${System.nanoTime()}.bin")
        file.writeBytes(payload)
        try {
            val actual = transport.sha256(file, GatewayCall())
            assertEquals("哈希必须是真算的、和服务端字节一致", digest, actual)
        } finally {
            file.delete()
        }
    }
}
