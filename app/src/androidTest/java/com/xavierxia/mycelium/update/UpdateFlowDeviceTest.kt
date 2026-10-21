package com.xavierxia.mycelium.update

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xavierxia.mycelium.net.AuditLog
import com.xavierxia.mycelium.net.HttpText
import com.xavierxia.mycelium.net.GatewayPurpose
import com.xavierxia.mycelium.net.OutboundGateway
import com.xavierxia.mycelium.net.openHttp
import com.xavierxia.mycelium.ui.MyceliumApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 工作包 1.3.2：整条更新流程的真机走查（Compose 设备测试）。
 *
 * 注入只存在于本文件：一个本机假发布源（127.0.0.1 的假服务）与一条「按正式包对待」的检查策略。
 * 调试包本身仍然不自动检查、也不提供应用内安装。
 *
 * 前置（由 `scripts/device/update-flow-prepare.ps1` 准备，不提交任何 APK）：
 *  * 把「versionCode = 6、包名仍是 com.xavierxia.mycelium.debug」的 APK 放到目标应用的
 *    `files/update-flow-v6.apk`（走 `run-as`，所以不需要外部存储权限）。
 *
 * 顺序：先跑不安装的两条（校验失败、取消），最后跑会真装的 happy path——装完这台机器上的
 * 调试包就变成 versionCode 6，走查完要把本分支的正常调试包装回去。
 */
@RunWith(AndroidJUnit4::class)
class UpdateFlowDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val evidenceDir: File
        get() = File(context.getExternalFilesDir(null), "update-flow").apply { mkdirs() }

    /** 每条用例都从干净状态开始：清掉自动检查时间戳、忽略项、候选与下载目录。 */
    @Before
    fun resetUpdateState() {
        val prefs = UpdatePreferences(context)
        prefs.autoCheck = true
        prefs.lastAutoAttemptAtMs = 0L
        prefs.lastSuccessAtMs = 0L
        prefs.ignoredTag = null
        prefs.clearPending()
        File(context.cacheDir, "updates").deleteRecursively()
    }

    private fun snap(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(evidenceDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 假发布源：`releases/latest` 用真解析路径，APK 与 .sha256 按名字流式发出。 */
    private class FakeReleaseService(
        private val apk: File,
        private val wrongChecksum: Boolean = false,
        private val slowBytesPerSecond: Int = 0,
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        private val accepted = AtomicInteger()
        val port: Int = server.localPort
        val sha256: String = digest(apk)

        /** 假服务上的 `releases/latest`：测试把它当发布源地址用。 */
        val releaseUrl: String get() = "http://127.0.0.1:$port/repos/fake/fake/releases/latest"

        private val releaseJson: String
            get() = """
                {
                  "tag_name": "v0.9.9",
                  "html_url": "http://127.0.0.1:$port/notes",
                  "body": "device walkthrough",
                  "published_at": "2026-10-21T00:00:00Z",
                  "assets": [
                    {"name": "mycelium-v0.9.9.apk",
                     "browser_download_url": "http://127.0.0.1:$port/apk",
                     "size": ${apk.length()}},
                    {"name": "mycelium-v0.9.9.apk.sha256",
                     "browser_download_url": "http://127.0.0.1:$port/apk.sha256",
                     "size": 65}
                  ]
                }
            """.trimIndent()

        init {
            Thread {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    accepted.incrementAndGet()
                    // 服务线程的顶层必须接住一切：客户端取消会写坏 socket，异常外逸会杀掉整个
                    // instrumentation 进程（真机上就是「跑不完」）。
                    Thread { runCatching { serve(socket) } }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        /** 已经接受的连接数：取消之后这个数不许再涨。 */
        fun connections(): Int = accepted.get()

        private fun serve(socket: Socket) {
            socket.use { client ->
                val raw = client.getInputStream()
                val head = StringBuilder()
                while (true) {
                    val next = raw.read()
                    if (next < 0) return
                    head.append(next.toChar())
                    if (head.endsWith("\r\n\r\n")) break
                }
                val path = head.lineSequence().first().split(" ").getOrNull(1).orEmpty()
                when {
                    path.startsWith("/repos/") -> respond(client, 200, releaseJson.toByteArray())
                    path == "/apk.sha256" -> {
                        val text = if (wrongChecksum) "0".repeat(64) else sha256
                        respond(client, 200, text.toByteArray())
                    }
                    path == "/apk" -> streamFile(client, apk, slowBytesPerSecond)
                    else -> respond(client, 404, ByteArray(0))
                }
            }
        }

        /** 60 MiB 的 APK 不进内存（测试进程的堆不大）：只说长度，然后分块从磁盘搬。 */
        private fun streamFile(client: Socket, file: File, throttleBytesPerSecond: Int) {
            val out: OutputStream = client.getOutputStream()
            val head = "HTTP/1.1 200 OK\r\nContent-Length: ${file.length()}\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray())
            val buffer = ByteArray(64 * 1024)
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    out.flush()
                    if (throttleBytesPerSecond > 0) {
                        // 目标速率靠「睡一个缓冲区的传输时间」逼近，够慢下来让人点「取消」。
                        Thread.sleep((buffer.size * 1000L / throttleBytesPerSecond).coerceAtLeast(1L))
                    }
                }
            }
        }

        private fun respond(
            client: Socket,
            status: Int,
            body: ByteArray,
            throttleBytesPerSecond: Int = 0,
        ) {
            val out: OutputStream = client.getOutputStream()
            val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray())
            var index = 0
            while (index < body.size) {
                val chunk = if (throttleBytesPerSecond > 0) minOf(throttleBytesPerSecond / 20, body.size - index) else body.size - index
                if (chunk <= 0) break
                out.write(body, index, chunk)
                out.flush()
                index += chunk
                if (throttleBytesPerSecond > 0) Thread.sleep(50)
            }
        }

        override fun close() = server.close()

        private companion object {
            fun digest(file: File): String =
                MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }
    }

    /** 让白名单放行本机 http 的假服务：这是测试专用的策略，应用里的 AllowedHosts 不放行它。 */
    private fun center(service: FakeReleaseService): UpdateCenter {
        val transport = GatewayUpdateTransport(
            OutboundGateway(
                hosts = com.xavierxia.mycelium.net.HostPolicy { uri, purpose ->
                    purpose == GatewayPurpose.Update && uri.host == "127.0.0.1"
                },
                audit = AuditLog(File(context.filesDir, "privacy/audit-flow.json")),
                clock = { "2026-10-21T00:00:00Z" },
                open = ::openHttp,
            ),
        )
        val prefs = UpdatePreferences(context)
        val files = UpdateFiles(File(context.cacheDir, "updates"), availableBytes = { Long.MAX_VALUE / 4 })
        // 发布源地址指向假服务：JSON 解析仍走 GitHubReleaseClient 的真路径（复审指出的漏接线）。
        val source = GitHubReleaseClient { _, _, _ -> httpGet(service.releaseUrl) }
        return UpdateCenter(
            updates = UpdateManager(source),
            prefs = prefs,
            isDebugPackage = false, // 「按正式包对待」：应用内安装入口才会出现
            files = files,
            downloader = GatewayApkDownloader(files, transport),
            verifier = LocalApkVerifier(
                transport = transport,
                archive = PackageManagerApkFactsReader(context),
                installed = CurrentAppFacts(context),
                selfPackageName = context.packageName,
            ),
            installer = AndroidInstallSessionGateway(context, prefs),
            sessionState = prefs,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            installedVersionCode = { versionCodeOf(context.packageName) },
        )
    }

    private fun httpGet(url: String): HttpText {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return try {
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpText(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun versionCodeOf(packageName: String): Long {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        return info.longVersionCode
    }

    private fun apkUnderTest(): File {
        val file = File(context.filesDir, "update-flow-v6.apk")
        assertTrue(
            "缺少走查用的 APK：先跑 scripts/device/update-flow-prepare.ps1（它把 versionCode=6 的调试包推到 ${file.absolutePath}）",
            file.isFile,
        )
        return file
    }

    /** 渲染真界面、切到「我的」、手动查一次（不依赖 24 小时的自动检查节流）。 */
    private fun renderAndCheck(center: UpdateCenter) {
        rule.setContent { MyceliumApp(center) }
        rule.onNodeWithText("我的").performClick()
        runBlocking { center.checkNow() }
        rule.waitUntil(timeoutMillis = 60_000) { center.state.value.release != null }
        // 红点是 BadgedBox 上的 testTag，合并语义树里会被父项吃掉，要按未合并树找。
        rule.onNodeWithTag("update_badge", useUnmergedTree = true).assertExists()
    }
    @Test
    fun aWrongChecksumIsRefusedAndNothingIsInstalled() {
        val apk = apkUnderTest()
        FakeReleaseService(apk, wrongChecksum = true).use { service ->
            val center = center(service)
            renderAndCheck(center)
            snap("2-卡片")

            rule.onNodeWithText("下载并安装").performClick()
            rule.waitUntil(timeoutMillis = 180_000) { center.state.value.download is DownloadUiState.Failed }

            val failed = center.state.value.download as DownloadUiState.Failed
            // 误区：错哈希这条路的 message 是 null（界面按 reason 生成文案），断言文案会必败。
            assertEquals(
                "错哈希要走「哈希不符」这条，不是别的失败",
                DownloadFailureReason.ChecksumMismatch,
                failed.reason,
            )
            snap("6-校验失败")
            assertFalse(center.state.value.download is DownloadUiState.Ready)
        }
    }

    @Test
    fun cancellingMidDownloadStopsTheTransferAndLeavesNoRunningTask() {
        val apk = apkUnderTest()
        FakeReleaseService(apk, slowBytesPerSecond = 1 shl 20).use { service ->
            val center = center(service)
            renderAndCheck(center)

            rule.onNodeWithText("下载并安装").performClick()
            rule.waitUntil(timeoutMillis = 60_000) { center.state.value.download is DownloadUiState.Running }
            rule.waitUntil(timeoutMillis = 60_000) {
                ((center.state.value.download as? DownloadUiState.Running)?.written ?: 0L) > 0L
            }
            snap("3-下载中")

            rule.onNodeWithText("取消").performClick()
            rule.waitUntil(timeoutMillis = 30_000) { center.state.value.download is DownloadUiState.Idle }

            // 只写 Idle 不代表任务真的退出：再看连接数停住、缓存文件也不再长大。
            val connections = service.connections()
            val sizeNow = newestCacheFileSize()
            Thread.sleep(2_000)
            assertEquals("取消后假服务不该再收到新连接", connections, service.connections())
            assertEquals("取消后缓存文件不该再长大", sizeNow, newestCacheFileSize())
            snap("7-取消后")
        }
    }

    private fun newestCacheFileSize(): Long =
        File(context.cacheDir, "updates").listFiles()?.maxOfOrNull { it.length() } ?: 0L

    /**
     * 整条走一遍。「下载并安装」是一键到底：校验一过就接着装，所以这里**不等稳定的 Ready**，
     * 而是等它落到「要先允许安装未知应用」或「正在安装」。用户点完系统界面的「安装」以后，
     * 系统会替换掉这个应用的进程 —— 本测试到此为止（后续核对由宿主机脚本做）。
     */
    @Test
    fun theWholeFlowReachesTheSystemInstaller() {
        val apk = apkUnderTest()
        assertTrue("起始调试包应该比走查包旧", versionCodeOf(context.packageName) < 6L)

        FakeReleaseService(apk).use { service ->
            val center = center(service)
            renderAndCheck(center)
            snap("1-红点与卡片")

            rule.onNodeWithText("下载并安装").performClick()
            rule.waitUntil(timeoutMillis = 60_000) { center.state.value.download is DownloadUiState.Running }
            snap("3-下载中-进度")

            rule.waitUntil(timeoutMillis = 300_000) {
                val state = center.state.value.download
                state is DownloadUiState.Installing || state is DownloadUiState.NeedsUnknownSources
            }
            if (center.state.value.download is DownloadUiState.NeedsUnknownSources) {
                snap("4a-未知来源说明")
                // 第一次要由用户在系统设置里允许；点「去设置」打开系统页，允许后回前台会接着装。
                rule.onNodeWithText("去设置").performClick()
                rule.waitUntil(timeoutMillis = 300_000) { center.state.value.download is DownloadUiState.Installing }
            }
            snap("5-系统确认")
            assertTrue(
                "应当停在「正在安装」等用户点系统界面的安装",
                center.state.value.download is DownloadUiState.Installing,
            )
        }
    }
}
