package com.xavierxia.mycelium.net

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 下载路径真的走网关那套取消闸门，用**数连接**而不是数字节的假对端来证。
 *
 * `f74e63e` 把下载的跳转走查（[HopWalker]）换成 [GatewayCall.bodyStream] 与
 * [GatewayCall.startingConnection]，并把超时归一化。这几条测例专门为「改回裸调用」而写：
 * 把其中任一处改回原来的裸 `connection.outputStream` / 裸 `connection.responseCode`，
 * 至少有一条必须变红（实证记录见交付说明）。
 *
 * 证到：
 *  * 状态还没取之前就取消 → 假对端**一次连接都没接受**（`LocalHttp.accepts`，不是字节数：
 *    「连上但一声不吭」是另一种结果，第一条测例末尾用真 socket 把它单独量出来）；
 *  * 取消落在「正在取请求正文流」的窗口里 → 连接已经发起、流已经拿到，正文仍然是**零字节**，
 *    调用以 [GatewayCancelled] 结束，且等待有上限（远小于两秒）；
 *  * 同样的接线不取消时，文件与正文都完整送达 —— 上面的零是取消造成的，不是夹具吞掉了一切。
 * 没证到：
 *  * 安卓能不能在建立过程中立刻结束一次连接 —— [ScriptedPeer] 只按 `disconnect()` 的意图建模，
 *    JVM 测试看不到真 socket；
 *  * 单独一行 `startingConnection { connection.responseCode }` 的判别力：走查在它前面紧挨着
 *    `call.check()`，对 GET 而言裸调用只在「与 `cancel()` 抢同一把锁」的那段竞态里才有区别，
 *    而那段竞态进不去确定性的测例。交付说明里按实测记录，不在这里假装覆盖。
 */
class DownloadCancelGateTest {
    @TempDir
    lateinit var directory: File

    private val update = GatewayPurpose.Update

    // ---- 1. 取消先于建连：假对端一次连接都没接受 ----

    @Test
    fun aStopBeforeTheStatusIsFetchedMeansThePeerNeverAcceptsAConnection() {
        val bytes = bodyBytes(16 * 1024)
        LocalHttp { HttpReply(200, bytes) }.use { server ->
            val call = GatewayCall()
            val file = target()
            // `open()` 是走查发起连接之前最后一次能被测试卡住的地方：连接对象已经交给网关登记，
            // 假对端还什么都没看见。在这里取消，就是「在 walk 真正取状态之前」能拿到的最晚时点。
            val gateway = gateway(policyFor(server)) { uri ->
                call.cancel()
                openHttp(uri)
            }
            val failure = assertThrows(GatewayCancelled::class.java) {
                gateway.download(
                    DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file, call = call),
                )
            }
            assertTrue(failure.message!!.contains("取消"), failure.message!!)
            // 真发起了连接的话，环回上的 accept 是微秒级的事；等满 300ms 还是零，才算真的没连。
            assertFalse(await(300) { server.accepts.get() > 0 }, "取消之后假对端还是接受到了连接")
            assertEquals(0, server.accepts.get(), "取消之后假对端接受了 ${server.accepts.get()} 次连接")
            assertEquals(0, server.count.get(), "取消之后还是发出了请求：${server.calls()}")
            assertFalse(file.exists(), "取消在状态之前，不该创建目标文件")
            val entry = auditEntries().single()
            assertNull(entry.status, "一个状态都没取到，不该记状态")
            assertFalse(entry.succeeded)
            assertEquals(0L, entry.receivedBytes)
            // M11：连接对象确实登记过，但它一次都没连出去 —— 所以审计里**不许**出现这个域名。
            // 「建连之前就取消」与「已联系」是两件事，前者写成后者就是把没发生的事记成事实。
            assertEquals(emptyList<String>(), entry.hostPath)

            // 夹具自证：连上却一声不吭的真 socket 会记进 accepts、不记进 count。有了这一条，
            // 上面的零才等于「没连」，而不是「连了但没写」被数漏了。
            Socket("127.0.0.1", server.port).use { }
            assertTrue(await { server.accepts.get() >= 1 }, "假对端没有记下「连上但一声不吭」的连接")
            assertEquals(0, server.count.get(), "一声不吭的连接被算成了一次请求")
        }
    }

    // ---- 2. 取消落在取流窗口：正文零字节，连接确实已经发起 ----

    @Test
    fun aStopWhileTheBodyStreamIsBeingOpenedLeavesZeroBodyBytesOnTheWire() {
        val marker = "MYCELIUM-REQUEST-BODY-MUST-NOT-LEAVE"
        val body = marker.toByteArray(Charsets.UTF_8)
        val enteredStream = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = GatewayCall()
        val peer = ScriptedPeer(pauseInOutputStream = enteredStream, releaseOutputStream = release)
        val walker = walkerFor(peer, call, body)
        val outcome = AtomicReference<Result<Hop>?>(null)
        val worker = thread(name = "download-body-stream-window") {
            outcome.set(runCatching { walker.walk(ASSET, null) })
        }
        assertTrue(enteredStream.await(5, TimeUnit.SECONDS), "没有走到取请求正文流")
        val started = System.nanoTime()
        call.cancel()
        release.countDown()
        worker.join(5_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertFalse(worker.isAlive, "取消之后取流窗口没有返回，等待没有上限")
        assertTrue(elapsedMs < 2_000, "取消后等了 ${elapsedMs}ms，没有马上结束")
        val failure = outcome.get()?.exceptionOrNull()
        assertTrue(failure is GatewayCancelled, "期望取消异常，实际是 $failure")
        assertEquals(0, peer.bytes, "取消之后请求正文还是写出去了：${peer.body}")
        // 不是零：这次取消落在取流窗口**里面**，建连已经发生。正因如此，这一条测的是取流后的
        // 复检与逐次写检查，而不是建连之前那道检查。
        assertEquals(1, peer.connects, "这次取消应当落在取流窗口里，而不是建连之前")

        // 阳性对照：同样的接线、不取消 → 正文完整送达，说明上面的零来自取消。
        val controlPeer = ScriptedPeer()
        val control = walkerFor(controlPeer, GatewayCall(), body).walk(ASSET, null)
        assertEquals(200, control.status)
        assertEquals(marker, controlPeer.body, "不取消时正文没有发出去")
        assertEquals(1, controlPeer.connects)
    }

    // ---- 3. 取消落在取状态窗口：不无限等待、磁盘零字节 ----

    @Test
    fun aStopWhileTheStatusIsBeingFetchedEndsTheWaitAndWritesNothing() {
        val call = GatewayCall()
        val statusEntered = CountDownLatch(1)
        val peer = ScriptedPeer(connectEntered = statusEntered, holdConnect = true)
        val gateway = gateway(HostPolicy { _, _ -> true }) { peer }
        val file = target()
        val outcome = AtomicReference<Result<DownloadResult>?>(null)
        val worker = thread(name = "download-status-window") {
            outcome.set(runCatching { gateway.download(DownloadRequest(ASSET.toString(), update, file, call = call)) })
        }
        assertTrue(statusEntered.await(5, TimeUnit.SECONDS), "没有走到取状态")
        val started = System.nanoTime()
        call.cancel()
        worker.join(5_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertFalse(worker.isAlive, "取消之后取状态没有返回，等待没有上限")
        assertTrue(elapsedMs < 2_000, "取消后等了 ${elapsedMs}ms，没有马上结束")
        val failure = outcome.get()?.exceptionOrNull()
        assertTrue(failure is GatewayCancelled, "期望取消异常，实际是 $failure")
        assertEquals(1, peer.connects, "应当恰好发起一次建连")
        assertEquals(0, peer.bytes, "还没取到状态，不该有任何字节")
        assertFalse(file.exists(), "取消发生在取状态时，不该留下目标文件")
        assertTrue(peer.disconnects >= 1, "取消没有断开正在取状态的那条连接")
        val entry = auditEntries().single()
        assertNull(entry.status)
        assertFalse(entry.succeeded)
        assertEquals(0L, entry.receivedBytes)
    }

    // ---- 4. 阳性对照：不取消，同样的下载完整成功 ----

    @Test
    fun aDownloadThatIsNotStoppedCompletesThroughTheSameGate() {
        val bytes = bodyBytes(64 * 1024, marker = "GATE-OPEN")
        LocalHttp { HttpReply(200, bytes) }.use { server ->
            val file = target()
            val result = gateway(policyFor(server)).download(
                DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file),
            )
            assertEquals(200, result.status)
            assertEquals(bytes.size.toLong(), result.bytesWritten)
            assertEquals(bytes.size.toLong(), result.fileSize)
            assertArrayEquals(bytes, file.readBytes(), "不取消时下载必须完整落盘")
            assertTrue(server.accepts.get() >= 1, "不取消时假对端必须见到连接")
            assertTrue(server.count.get() >= 1, "不取消时假对端必须见到请求")
            val entry = auditEntries().single()
            assertTrue(entry.succeeded)
            assertEquals(bytes.size.toLong(), entry.receivedBytes)
        }
    }

    // ---- 5. 同一提交里的超时归一化，也要在下载路径上钉住 ----

    @Test
    fun theDownloadPutsBoundedTimeoutsOnItsConnection() {
        // 下载以前把调用方给的值直接交给 socket，而 0 在那里等于「永远等」。这条钉住归一化后的
        // 真实值，让「退回无界建连」在下载路径上也看得见，而不只是网关自己的 send()。
        val opened = mutableListOf<HttpURLConnection>()
        LocalHttp { HttpReply(200, bodyBytes(4096)) }.use { server ->
            val gateway = gateway(policyFor(server)) { uri -> openHttp(uri).also { opened += it } }
            listOf(0 to 0, 600_000 to 0).forEachIndexed { index, (connect, read) ->
                gateway.download(
                    DownloadRequest(
                        url = "http://127.0.0.1:${server.port}/asset-$index",
                        purpose = update,
                        target = target("updates/a-$index.apk"),
                        connectTimeoutMs = connect,
                        readTimeoutMs = read,
                    ),
                )
            }
        }
        val effective = opened.map { it.connectTimeout to it.readTimeout }
        assertEquals(2, effective.size)
        assertTrue(effective.all { (connect, read) -> connect > 0 && read > 0 }, "无限等待漏到了下载的 socket：$effective")
        assertEquals(
            listOf(
                GatewayRequest.DEFAULT_CONNECT_TIMEOUT_MS to GatewayRequest.DEFAULT_READ_TIMEOUT_MS,
                GatewayRequest.MAX_CONNECT_TIMEOUT_MS to GatewayRequest.DEFAULT_READ_TIMEOUT_MS,
            ),
            effective,
        )
    }

    // ---- helpers ----

    private fun gateway(policy: HostPolicy, open: (URI) -> HttpURLConnection = ::openHttp): OutboundGateway =
        OutboundGateway(
            hosts = policy,
            audit = AuditLog(File(directory, "privacy/audit.json")),
            clock = { "2026-10-19T00:00:00Z" },
            open = open,
        )

    /** 生产规则的形状：只允许本测例的环回地址通过。 */
    private fun policyFor(server: LocalHttp) = HostPolicy { uri, _ -> uri.host == "127.0.0.1" && uri.port == server.port }

    /**
     * 下载的跳转走查本体。`download()` 发的是 GET、没有请求正文，所以它永远走不到
     * [HopWalker] 里写正文那一段（见交付说明第 ④ 条）；这一段只由同一个走查的元数据用途
     * （[readFollowingRedirects] 带 body 时）到达。这里直接驱动走查，是为了让「改回裸
     * `connection.outputStream`」必须变红。
     */
    private fun walkerFor(peer: HttpURLConnection, call: GatewayCall, body: ByteArray): HopWalker = HopWalker(
        gateway = gateway(HostPolicy { _, _ -> true }) { peer },
        purpose = update,
        call = call,
        method = "POST",
        headers = emptyMap(),
        body = body,
        connectTimeoutMs = GatewayRequest.DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs = GatewayRequest.DEFAULT_READ_TIMEOUT_MS,
    )

    private fun target(name: String = "updates/mycelium-v0.4.0.apk") = File(directory, name)

    private fun bodyBytes(size: Int, marker: String? = null): ByteArray {
        val bytes = ByteArray(size) { index -> (index % 251).toByte() }
        if (marker != null) marker.toByteArray(Charsets.UTF_8).copyInto(bytes, size / 3)
        return bytes
    }

    private fun auditEntries(): List<AuditEntry> = AuditLog(File(directory, "privacy/audit.json")).read()

    /** 轮询到条件成立或超时；返回条件最终是否成立。用来把「没有发生」也变成有上限的断言。 */
    private fun await(timeoutMs: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private companion object {
        val ASSET: URI = URI("https://api.deepseek.com/asset")
    }
}

/**
 * 只建模这几条测例需要的东西的假对端：
 *
 *  * 建连在 `getOutputStream()` / `getResponseCode()` 里发起，并在 [connects] 里记数 —— 和真的
 *    `HttpURLConnection`（以及安卓那份）一样；
 *  * [connectEntered] + [holdConnect] 把已经发起的 `connect()` 卡在调用里，只有 `disconnect()`
 *    能放它走 —— 这是对平台行为的**假设**，不是观测到的结论；
 *  * [pauseInOutputStream] + [releaseOutputStream] 把线程卡在「连接已发起、正文一个字节还没写」
 *    的那一刻，也就是取流窗口的正中间；
 *  * 还有平台那个不改的性质：什么都没发起时的 `disconnect()` 不会阻止之后的 `connect()`。
 */
private class ScriptedPeer(
    private val connectEntered: CountDownLatch? = null,
    private val holdConnect: Boolean = false,
    private val pauseInOutputStream: CountDownLatch? = null,
    private val releaseOutputStream: CountDownLatch? = null,
) : HttpURLConnection(URI("https://api.deepseek.com/asset").toURL()) {
    private val received = ByteArrayOutputStream()
    private val interrupted = CountDownLatch(1)

    @Volatile
    private var established = false

    @Volatile
    private var connectInFlight = false

    @Volatile
    var connects: Int = 0
        private set

    @Volatile
    var disconnects: Int = 0
        private set

    val bytes: Int get() = synchronized(received) { received.size() }

    val body: String get() = synchronized(received) { String(received.toByteArray(), Charsets.UTF_8) }

    override fun getOutputStream(): OutputStream {
        connect()
        pauseInOutputStream?.countDown()
        releaseOutputStream?.await(5, TimeUnit.SECONDS)
        return object : OutputStream() {
            override fun write(b: Int) {
                synchronized(received) { received.write(b) }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                synchronized(received) { received.write(b, off, len) }
            }
        }
    }

    override fun getResponseCode(): Int {
        connect()
        return 200
    }

    override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())

    override fun getErrorStream(): InputStream? = null

    override fun disconnect() {
        disconnects += 1
        if (connectInFlight) interrupted.countDown()
    }

    override fun usingProxy(): Boolean = false

    override fun connect() {
        if (established) return
        connects += 1
        connectInFlight = true
        connectEntered?.countDown()
        if (holdConnect) {
            val released = interrupted.await(5, TimeUnit.SECONDS)
            connectInFlight = false
            if (!released) throw IOException("假对端的建连在等待上限内没有被断开")
            throw IOException("连接在建立过程中被断开")
        }
        connectInFlight = false
        established = true
    }
}
