package com.xavierxia.mycelium.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * M11 的两条台账测例：
 *  1. 元数据读到一半就断了，台账要按**已经读到的字节**记账，不能记 0、也不能记声明的总长；
 *  2. 连都没建立起来的记录，落盘后仍然保持空链——「没联系」不许写成「联系过」。
 */
class AuditByteAccountingTest {
    @TempDir
    lateinit var directory: File

    /** 先报一个比实际内容大的 Content-Length，发一半就把连接掐掉。 */
    private class TruncatingPeer(private val declared: Int, private val actual: Int, private val body: ByteArray) {
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort
        private val done = CountDownLatch(1)

        fun start() {
            thread(isDaemon = true, name = "truncating-peer") {
                try {
                    server.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(4096))
                        val head = ("HTTP/1.1 200 OK\r\nContent-Length: $declared\r\n\r\n").toByteArray()
                        socket.getOutputStream().write(head)
                        socket.getOutputStream().write(body, 0, actual)
                        socket.getOutputStream().flush()
                    }
                } finally {
                    done.countDown()
                    runCatching { server.close() }
                }
            }
        }

        /** 等最后一段真的写完再关：不然读到的一半字节会变成 0。 */
        fun await() {
            assertTrue(done.await(5, TimeUnit.SECONDS), "假服务端没有跑完")
        }
    }

    private fun gateway(port: Int) = OutboundGateway(
        hosts = HostPolicy { uri, _ -> uri.host == "127.0.0.1" && uri.port == port },
        audit = AuditLog(File(directory, "privacy/audit.json")),
        clock = { "2026-10-20T00:00:00Z" },
        open = ::openHttp,
    )

    private fun auditEntries(): List<AuditEntry> = AuditLog(File(directory, "privacy/audit.json")).read()

    @Test
    @Timeout(30)
    fun aHalfReadMetadataBodyIsRecordedWithTheBytesThatActuallyArrived() {
        val body = ByteArray(100) { 'a'.code.toByte() }
        val peer = TruncatingPeer(declared = 4096, actual = body.size, body = body)
        peer.start()
        val gateway = gateway(peer.port)

        val failure = runCatching {
            gateway.readFollowingRedirects(
                GatewayRequest(url = "http://127.0.0.1:${peer.port}/release.sha256", purpose = GatewayPurpose.Update),
            )
        }
        peer.await()
        assertTrue(failure.isFailure, "内容没读完，这次调用必须失败：$failure")

        val entry = auditEntries().last()
        assertFalse(entry.succeeded)
        assertEquals(
            100L,
            entry.receivedBytes,
            "台账要记真正读到的 100 字节，不能记 0，也不能记声明的 4096",
        )
        assertEquals(0, entry.sentCharacters)
    }

    @Test
    @Timeout(30)
    fun aRequestThatNeverConnectedKeepsAnEmptyHostChainAfterItIsPersisted() {
        // 端口是刚关掉的：连接一定失败，一次状态码都没拿到。
        val closed = ServerSocket(0)
        val port = closed.localPort
        closed.close()

        val gateway = gateway(port)
        val failure = runCatching {
            gateway.readFollowingRedirects(
                GatewayRequest(url = "http://127.0.0.1:$port/release.sha256", purpose = GatewayPurpose.Update),
            )
        }
        assertTrue(failure.isFailure, "连不上就该失败：$failure")

        val entry = auditEntries().single()
        assertFalse(entry.succeeded)
        assertEquals(0L, entry.receivedBytes)
        assertTrue(entry.hostPath.isEmpty(), "一次都没连出去，链必须是空的：${entry.hostPath}")
    }
}
