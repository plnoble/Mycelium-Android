package com.xavierxia.mycelium.net

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假服务自己的线程不许把测试进程带走。Android 上任意线程的未捕获异常都会杀掉整个进程（真机上就是
 * 一次 instrumentation 跑不完），所以「脚本 handler 抛异常」和「客户端在半途断开」这两件事都必须
 * 停在 [LocalHttp] 自己的 catch 里：前者记录，后者安静收尾。
 */
class LocalHttpTest {
    private val escaped = Collections.synchronizedList(mutableListOf<Throwable>())
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

    /** 任何线程顶层逃出来的异常都会在这里留痕：这就是真机上会杀进程的那条路。 */
    @BeforeEach
    fun watchForEscapes() {
        Thread.setDefaultUncaughtExceptionHandler { _, error -> escaped += error }
    }

    @AfterEach
    fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test
    fun aHandlerThatBlowsUpIsRecordedInsteadOfEscapingTheThread() {
        // 只有第一条连接上炸：第二条要证明假服务自己没有被那条坏连接带走。
        val seen = AtomicInteger()
        LocalHttp {
            if (seen.incrementAndGet() == 1) throw IllegalStateException("scripted handler blew up")
            HttpReply(200, """{"ok":true}""")
        }.use { peer ->
            assertEquals(-1, request(peer.port, "/boom"), "handler 抛异常之后还回了响应")
            val problems = awaitProblems(peer)
            // 记录发生在 catch 里，所以 problems() 非空时，逃逸那条路（如果有）已经走完了。
            assertEquals(1, problems.size, "handler 的异常既没被记录，也没留痕")
            assertTrue(problems.single() is IllegalStateException, "记录下来的不是 handler 抛的那个异常")
            assertTrue(escaped.isEmpty(), "异常逃到了线程顶层：$escaped")
            assertEquals(200, request(peer.port, "/next"), "一条连接炸了之后假服务就不再服务了")
        }
    }

    @Test
    fun aClientThatHangsUpMidAnswerIsQuietlyDropped() {
        val handlerEntered = CountDownLatch(1)
        // 比 socket 发送缓冲大得多：客户端不读，服务线程一定阻塞在这次写里。
        val huge = ByteArray(8 * 1024 * 1024) { 'x'.code.toByte() }
        LocalHttp {
            handlerEntered.countDown()
            HttpReply(200, huge)
        }.use { peer ->
            Socket("127.0.0.1", peer.port).use { client ->
                client.getOutputStream().write(
                    "GET /huge HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
                )
                client.getOutputStream().flush()
                assertTrue(handlerEntered.await(5, TimeUnit.SECONDS), "假服务没有处理这个请求")
                // close() 发 RST 而不是 FIN：服务线程卡住的那次写会当场失败（Broken pipe）。
                client.setSoLinger(true, 0)
            }
            // 循环回环上的 RST 立刻就到；给写失败一秒，然后确认它安静地结束了这条连接。
            Thread.sleep(1_000)
            assertTrue(escaped.isEmpty(), "客户端断开时异常逃到了线程顶层：$escaped")
            assertTrue(peer.problems().isEmpty(), "客户端断开被当成了错误：${peer.problems()}")
            assertEquals(200, request(peer.port, "/next"), "一条连接被重置之后假服务就不再服务了")
        }
    }

    /** 一次真实的本机请求；拿不到状态行（对端直接关了）返回 -1。 */
    private fun request(port: Int, path: String): Int {
        val text = Socket("127.0.0.1", port).use { client ->
            client.getOutputStream().write(
                "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
            )
            client.getOutputStream().flush()
            String(client.getInputStream().readBytes(), StandardCharsets.UTF_8)
        }
        val statusLine = text.lineSequence().firstOrNull().orEmpty()
        return statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
    }

    private fun awaitProblems(peer: LocalHttp): List<Throwable> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val problems = peer.problems()
            if (problems.isNotEmpty()) return problems
            Thread.sleep(10)
        }
        return peer.problems()
    }
}
