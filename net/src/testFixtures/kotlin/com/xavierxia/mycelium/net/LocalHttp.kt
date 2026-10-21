package com.xavierxia.mycelium.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/** Scripted HTTP peer for tests. The gateway still opens the outbound connection. */
class LocalHttp(private val handler: (HttpCall) -> HttpReply) : AutoCloseable {
    private val server = ServerSocket(0)
    private val calls = mutableListOf<HttpCall>()
    private val problems = mutableListOf<Throwable>()

    /** HTTP requests that arrived complete enough to have a request line. */
    val count = AtomicInteger()

    /**
     * TCP connections accepted, whether or not the client ever sent a request line.
     *
     * [count] cannot tell "never connected" apart from "connected and hung up without a request",
     * because the accept itself only shows up when a request line arrives. A test that has to show a
     * stop before the connection is started needs this counter; a test that tells the two apart
     * against a real client can open a socket, write nothing, and watch this go up while [count]
     * stays at zero.
     */
    val accepts = AtomicInteger()

    val port: Int = server.localPort

    init {
        Thread {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    // close() is what makes accept fail; the server is shutting down and so is the loop.
                    break
                } catch (problem: Exception) {
                    record(problem)
                    break
                }
                accepts.incrementAndGet()
                Thread {
                    // 服务线程的顶层：这里接住一切，不许再往外走。Android 上任意线程的未捕获异常
                    // 都会杀掉整个进程——真机上就是一次 instrumentation 跑不完。
                    try {
                        serve(socket)
                    } catch (_: IOException) {
                        // 客户端断开、连接被取消：这条连接到此为止，安静收尾。
                    } catch (problem: Exception) {
                        record(problem)
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    fun calls(): List<HttpCall> = synchronized(calls) { calls.toList() }

    /**
     * Everything the service threads caught instead of rethrowing. A client that hangs up is not a
     * problem (see [serve]), so this stays empty for the ordinary "the peer went away" cases; a
     * broken scripted handler shows up here rather than taking the whole test process down.
     */
    fun problems(): List<Throwable> = synchronized(problems) { problems.toList() }

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
            // 连上就挂断、一个字节请求行都没发的客户端没有什么可回答的（它也不该算一次错误）。
            val (method, path) = lines.first().split(" ").let { parts ->
                if (parts.size < 2) return else parts[0] to parts[1]
            }
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
            val bytes = reply.body
            val declared = reply.headers.entries
                .firstOrNull { (name, _) -> name.equals("Content-Length", ignoreCase = true) }
                ?.value
                ?.toIntOrNull()
            val statusHead = buildString {
                // The status line must be legal for every status a test returns; anything outside the
                // codes the download path really needs keeps a generic but valid reason phrase.
                append("HTTP/1.1 ${reply.status} ${reasonPhrase(reply.status)}\r\n")
                // Content-Length is written exactly once, even when the reply carries its own.
                append("Content-Length: ${declared ?: bytes.size}\r\n")
                reply.headers.forEach { (name, value) ->
                    if (!name.equals("Content-Length", ignoreCase = true)) append("$name: $value\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            client.getOutputStream().write(statusHead.toByteArray(StandardCharsets.UTF_8))
            client.getOutputStream().write(bytes)
        }
    }

    private fun record(problem: Throwable) {
        synchronized(problems) { problems += problem }
        System.err.println("LocalHttp: the scripted peer caught ${problem.javaClass.name}: ${problem.message}")
        problem.printStackTrace()
    }
}

/** Only the codes the gateway tests need are spelled out; the rest stay a legal generic phrase. */
private fun reasonPhrase(status: Int): String = when (status) {
    200 -> "OK"
    206 -> "Partial Content"
    302 -> "Found"
    303 -> "See Other"
    307 -> "Temporary Redirect"
    308 -> "Permanent Redirect"
    416 -> "Range Not Satisfiable"
    else -> "Unknown"
}

data class HttpCall(val method: String, val path: String, val headers: Map<String, String>, val body: String)

/**
 * One scripted answer. The body is bytes, not text, because the download tests move real binaries;
 * the [String] overload below keeps every existing test source-compatible.
 */
data class HttpReply(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    constructor(status: Int, body: String) : this(status, body.toByteArray(StandardCharsets.UTF_8))
}
