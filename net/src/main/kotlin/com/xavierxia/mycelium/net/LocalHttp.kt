package com.xavierxia.mycelium.net

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/** Scripted HTTP peer for tests. The gateway still opens the outbound connection. */
class LocalHttp(private val handler: (HttpCall) -> HttpReply) : AutoCloseable {
    private val server = ServerSocket(0)
    private val calls = mutableListOf<HttpCall>()
    val count = AtomicInteger()

    val port: Int = server.localPort

    init {
        Thread {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                Thread { serve(socket) }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    fun calls(): List<HttpCall> = synchronized(calls) { calls.toList() }

    override fun close() = server.close()

    private fun serve(socket: Socket) {
        socket.use { client ->
            val raw = client.getInputStream()
            val head = ByteArrayOutputStream()
            while (true) {
                val next = raw.read()
                if (next < 0) break
                head.write(next)
                val written = head.toByteArray()
                if (written.size >= 4 && written.copyOfRange(written.size - 4, written.size).contentEquals(byteArrayOf(13, 10, 13, 10))) break
            }
            val headerText = head.toString(StandardCharsets.UTF_8)
            val lines = headerText.split("\r\n", "\n")
            val (method, path) = lines.first().split(" ").let { it[0] to it[1] }
            val headers = lines.drop(1).mapNotNull { line ->
                val split = line.indexOf(':')
                if (split > 0) line.substring(0, split).lowercase() to line.substring(split + 1).trim() else null
            }.toMap()
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val got = raw.read(bodyBytes, read, length - read)
                if (got < 0) break
                read += got
            }
            val call = HttpCall(method, path, headers, String(bodyBytes, 0, read, StandardCharsets.UTF_8))
            synchronized(calls) { calls += call }
            count.incrementAndGet()
            val reply = handler(call)
            val bytes = reply.body.toByteArray(StandardCharsets.UTF_8)
            client.getOutputStream().write(
                "HTTP/1.1 ${reply.status} OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    .toByteArray(StandardCharsets.UTF_8),
            )
            client.getOutputStream().write(bytes)
        }
    }
}

data class HttpCall(val method: String, val path: String, val headers: Map<String, String>, val body: String)

data class HttpReply(val status: Int, val body: String)
