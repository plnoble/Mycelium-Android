package com.xavierxia.mycelium.net

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
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GatewayTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun rejectsHostsOutsideTheWhitelist() {
        val gateway = gateway("https://api.deepseek.com")
        val rejected = assertThrows(GatewayRejected::class.java) {
            gateway.send(GatewayRequest("https://example.com/v1", GatewayPurpose.RoundtableChat, body = "secret-key"))
        }
        assertTrue(rejected.message!!.contains("example.com"))
        assertTrue(AuditLog(File(directory, "privacy/audit.json")).read().isEmpty())
    }

    @Test
    fun chatHostCannotBeUsedForTheUpdateCheck() {
        val hosts = AllowedHosts("https://api.deepseek.com", allowCleartextLocalhost = false)
        assertFalse(hosts.allows(URI("https://api.deepseek.com/chat/completions"), GatewayPurpose.Update))
        assertTrue(hosts.allows(URI("https://api.github.com/repos/x/releases/latest"), GatewayPurpose.Update))
        assertTrue(hosts.allows(URI("https://github.com/x/releases/download/a.apk"), GatewayPurpose.Update))
        assertTrue(hosts.allows(URI("https://objects.githubusercontent.com/a.apk"), GatewayPurpose.Update))
        assertFalse(hosts.allows(URI("https://example.com/a.apk"), GatewayPurpose.Update))
        assertFalse(hosts.allows(URI("http://api.github.com/repos/x"), GatewayPurpose.Update))
    }

    @Test
    fun everySavedServiceHostIsAllowedForModelsAndChat() {
        val hosts = AllowedHosts(listOf("https://api.deepseek.com", "https://api.example.net"), allowCleartextLocalhost = false)
        assertTrue(hosts.allows(URI("https://api.deepseek.com/models"), GatewayPurpose.FetchModels))
        assertTrue(hosts.allows(URI("https://api.example.net/models"), GatewayPurpose.FetchModels))
        assertTrue(hosts.allows(URI("https://api.example.net/chat/completions"), GatewayPurpose.RoundtableChat))
        assertFalse(hosts.allows(URI("https://other.example.com/models"), GatewayPurpose.FetchModels))
        assertFalse(hosts.allows(URI("https://api.github.com/models"), GatewayPurpose.FetchModels))
    }

    @Test
    fun recordsEveryChatCallWithoutTheKey() {
        LocalHttp { call ->
            assertEquals("POST", call.method)
            assertFalse(call.body.contains("sk-test"))
            HttpReply(200, """{"choices":[{"message":{"content":"{}"}}],"usage":{"prompt_tokens":10,"completion_tokens":4}}""")
        }.use { server ->
            val gateway = gateway("http://127.0.0.1:${server.port}", cleartext = true)
            val response = gateway.send(
                GatewayRequest(
                    url = "http://127.0.0.1:${server.port}/chat/completions",
                    purpose = GatewayPurpose.RoundtableChat,
                    method = "POST",
                    headers = mapOf("Authorization" to "Bearer sk-test"),
                    body = """{"model":"deepseek-flash","messages":[]}""",
                ),
            )
            assertEquals(200, response.status)
            val entry = AuditLog(File(directory, "privacy/audit.json")).read().single()
            assertEquals("127.0.0.1", entry.host)
            assertEquals(GatewayPurpose.RoundtableChat, entry.purpose)
            assertTrue(entry.succeeded)
            assertFalse(entry.sentBody.contains("sk-test"))
            assertFalse(File(directory, "privacy/audit.json").readText().contains("Authorization"))
            // Usage is recorded, not turned into a cost or a block.
            assertEquals(10, entry.promptTokens)
            assertEquals(4, entry.completionTokens)
        }
    }

    @Test
    fun modelListPullIsAuditedWithItsPurposeAndNoKey() {
        LocalHttp { call ->
            assertEquals("GET", call.method)
            assertEquals("/models", call.path)
            assertEquals("Bearer sk-test", call.headers["authorization"])
            HttpReply(200, """{"data":[{"id":"deepseek-flash"},{"id":"deepseek-pro"}]}""")
        }.use { server ->
            val gateway = gateway("http://127.0.0.1:${server.port}", cleartext = true)
            val response = gateway.send(
                GatewayRequest(
                    url = "http://127.0.0.1:${server.port}/models",
                    purpose = GatewayPurpose.FetchModels,
                    headers = mapOf("Authorization" to "Bearer sk-test"),
                ),
            )
            assertEquals(200, response.status)
            val entry = AuditLog(File(directory, "privacy/audit.json")).read().single()
            assertEquals(GatewayPurpose.FetchModels, entry.purpose)
            assertTrue(entry.succeeded)
            assertFalse(File(directory, "privacy/audit.json").readText().contains("sk-test"))
            assertNull(entry.promptTokens)
        }
    }

    @Test
    fun malformedUsageIsNotRecorded() {
        LocalHttp { HttpReply(200, """{"choices":[{"message":{"content":"{}"}}],"usage":{"prompt_tokens":"not-a-number"}}""") }.use { server ->
            val gateway = gateway("http://127.0.0.1:${server.port}", cleartext = true)
            gateway.send(GatewayRequest("http://127.0.0.1:${server.port}/chat/completions", GatewayPurpose.RoundtableChat, method = "POST", body = "{}"))
        }
        val entry = AuditLog(File(directory, "privacy/audit.json")).read().single()
        assertEquals(0, entry.promptTokens)
        assertEquals(0, entry.completionTokens)
    }

    @Test
    fun cleartextIsOnlyLoopbackAndOnlyWhenDebugAllowsIt() {
        val release = AllowedHosts("http://127.0.0.1:9", allowCleartextLocalhost = false)
        assertFalse(release.allows(URI("http://127.0.0.1:9/chat"), GatewayPurpose.RoundtableChat))
        assertFalse(release.allows(URI("http://127.0.0.1:9/models"), GatewayPurpose.FetchModels))
        val debug = AllowedHosts("http://127.0.0.1:9", allowCleartextLocalhost = true)
        assertTrue(debug.allows(URI("http://127.0.0.1:9/chat"), GatewayPurpose.RoundtableChat))
        assertTrue(debug.allows(URI("http://127.0.0.1:9/models"), GatewayPurpose.FetchModels))
        assertFalse(debug.allows(URI("http://10.0.0.8/chat"), GatewayPurpose.RoundtableChat))
        assertFalse(debug.allows(URI("http://localhost/chat"), GatewayPurpose.RoundtableChat))
    }

    @Test
    fun transportFailureIsAudited() {
        val gateway = gateway("http://127.0.0.1:1", cleartext = true)
        assertThrows(Exception::class.java) {
            gateway.send(GatewayRequest("http://127.0.0.1:1/chat", GatewayPurpose.RoundtableChat, method = "POST", body = "问"))
        }
        val entry = AuditLog(File(directory, "privacy/audit.json")).read().single()
        assertFalse(entry.succeeded)
        assertEquals(null, entry.status)
        assertEquals("问", entry.sentBody)
    }

    @Test
    fun aRequestCanAskForNoBodyInTheAuditAndItAlsoHoldsWhenTheCallFails() {
        // A request-level option, independent of the purpose: the service still gets the text, the
        // audit keeps only its size. Here the call cannot even connect, which is the path that used
        // to write the body unconditionally.
        val gateway = gateway("http://127.0.0.1:1", cleartext = true)
        assertThrows(Exception::class.java) {
            gateway.send(
                GatewayRequest(
                    url = "http://127.0.0.1:1/chat/completions",
                    purpose = GatewayPurpose.HearthChat,
                    method = "POST",
                    body = """{"messages":[{"role":"user","content":"我今天很难过"}]}""",
                    omitAuditBody = true,
                ),
            )
        }
        val entry = AuditLog(File(directory, "privacy/audit.json")).read().single()
        assertEquals("", entry.sentBody)
        assertTrue(entry.sentCharacters > 0)
        assertFalse(File(directory, "privacy/audit.json").readText().contains("难过"))
    }

    @Test
    fun hearthChatUsesTheModelHostsAndTheTestModeKeepsTheBodyOutOfTheAudit() {
        val hosts = AllowedHosts(listOf("https://api.deepseek.com"), allowCleartextLocalhost = false)
        assertTrue(hosts.allows(URI("https://api.deepseek.com/chat/completions"), GatewayPurpose.HearthChat))
        assertTrue(hosts.allows(URI("https://api.deepseek.com/chat/completions"), GatewayPurpose.HearthChatTest))
        assertFalse(hosts.allows(URI("https://api.github.com/chat/completions"), GatewayPurpose.HearthChat))

        LocalHttp { HttpReply(200, """{"choices":[{"message":{"content":"{}"}}]}""") }.use { server ->
            val gateway = gateway("http://127.0.0.1:${server.port}", cleartext = true)
            gateway.send(
                GatewayRequest(
                    url = "http://127.0.0.1:${server.port}/chat/completions",
                    purpose = GatewayPurpose.HearthChatTest,
                    method = "POST",
                    body = """{"model":"deepseek-flash","messages":[{"role":"user","content":"我有点撑不住了"}]}""",
                ),
            )
        }
        val entry = AuditLog(File(directory, "privacy/audit.json")).read().single()
        assertEquals(GatewayPurpose.HearthChatTest, entry.purpose)
        assertTrue(entry.succeeded)
        // Time, host, purpose, size and status are kept; the text itself is not.
        assertEquals("", entry.sentBody)
        assertTrue(entry.sentCharacters > 0)
        assertFalse(File(directory, "privacy/audit.json").readText().contains("撑不住"))
    }

    @Test
    fun theHearthImmersiveModeStillRecordsTheBody() {
        LocalHttp { HttpReply(200, """{"choices":[{"message":{"content":"{}"}}]}""") }.use { server ->
            val gateway = gateway("http://127.0.0.1:${server.port}", cleartext = true)
            gateway.send(
                GatewayRequest(
                    url = "http://127.0.0.1:${server.port}/chat/completions",
                    purpose = GatewayPurpose.HearthChat,
                    method = "POST",
                    body = """{"model":"deepseek-flash","messages":[{"role":"user","content":"今天很累"}]}""",
                ),
            )
        }
        val entry = AuditLog(File(directory, "privacy/audit.json")).read().single()
        assertEquals(GatewayPurpose.HearthChat, entry.purpose)
        assertTrue(entry.sentBody.contains("今天很累"))
    }

    @Test
    fun aCancelledRequestNeverOpensAConnection() {
        // The switch outlives the connection: cancelling before the gateway is even called must stop
        // the request there, not after a socket that would have gone out anyway.
        val call = GatewayCall()
        var opened = false
        val gateway = OutboundGateway(
            hosts = AllowedHosts("https://api.deepseek.com", allowCleartextLocalhost = false),
            audit = AuditLog(File(directory, "privacy/audit.json")),
            clock = { "2026-10-18T00:00:00Z" },
            open = { opened = true; throw IllegalStateException("取消的请求不该打开连接") },
        )
        call.cancel()
        val failure = assertThrows(GatewayCancelled::class.java) {
            gateway.send(
                GatewayRequest(
                    url = "https://api.deepseek.com/chat/completions",
                    purpose = GatewayPurpose.RoundtableChat,
                    method = "POST",
                    body = "这句不该发出去",
                    call = call,
                ),
            )
        }
        assertTrue(failure.message!!.contains("取消"), failure.message!!)
        assertFalse(opened, "取消的请求打开了连接")
        // The audit follows its existing rules: the request was attempted, so it is recorded as a
        // failure with its purpose, its size and the body the caller was willing to have stored.
        val entry = AuditLog(File(directory, "privacy/audit.json")).read().single()
        assertEquals(GatewayPurpose.RoundtableChat, entry.purpose)
        assertFalse(entry.succeeded)
        assertNull(entry.status)
        assertTrue(entry.sentCharacters > 0)
        assertEquals("这句不该发出去", entry.sentBody)
    }

    @Test
    fun aStopInTheRegistrationWindowNeverWritesTheBody() {
        // The window the review named: the request is already inside the gateway and the connection
        // object exists, but `open` has not returned, so no handle has been handed over and not one
        // byte has been written. A stop here must still stop the body: the switch, not the handle,
        // is what the writer checks, and `disconnect()` on a connection that never connected does
        // nothing at all.
        val insideOpen = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = GatewayCall()
        LocalHttp { HttpReply(200, """{"choices":[{"message":{"content":"{}"}}]}""") }.use { server ->
            val gateway = OutboundGateway(
                hosts = AllowedHosts("http://127.0.0.1:${server.port}", allowCleartextLocalhost = true),
                audit = AuditLog(File(directory, "privacy/audit.json")),
                clock = { "2026-10-18T00:00:00Z" },
                open = { uri ->
                    val connection = openHttp(uri)
                    insideOpen.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    connection
                },
            )
            val outcome = AtomicReference<Result<GatewayResponse>?>(null)
            val worker = thread(name = "gateway-registration-window") {
                outcome.set(
                    runCatching {
                        gateway.send(
                            GatewayRequest(
                                url = "http://127.0.0.1:${server.port}/chat/completions",
                                purpose = GatewayPurpose.RoundtableChat,
                                method = "POST",
                                body = """{"messages":[{"role":"user","content":"这句不该发出去"}]}""",
                                call = call,
                            ),
                        )
                    },
                )
            }
            assertTrue(insideOpen.await(5, TimeUnit.SECONDS), "网关没有走到 open")
            call.cancel()
            release.countDown()
            worker.join(5_000)
            assertFalse(worker.isAlive, "取消之后网关没有返回")
            val failure = outcome.get()?.exceptionOrNull()
            assertTrue(failure is GatewayCancelled, "期望取消异常，实际是 $failure")
            assertEquals(0, server.count.get(), "取消之后还是把正文写出去了")
            assertTrue(server.calls().isEmpty(), "假服务收到了 ${server.calls()}")

            // Positive control: the same gateway with a fresh switch does deliver the body, so the
            // zero above is the cancel and not a harness that never delivers anything.
            val delivered = gateway.send(
                GatewayRequest(
                    url = "http://127.0.0.1:${server.port}/chat/completions",
                    purpose = GatewayPurpose.RoundtableChat,
                    method = "POST",
                    body = """{"messages":[{"role":"user","content":"正常正文"}]}""",
                    call = GatewayCall(),
                ),
            )
            assertEquals(200, delivered.status)
            assertEquals(1, server.count.get())
            assertTrue(server.calls().single().body.contains("正常正文"))
        }
        val entries = AuditLog(File(directory, "privacy/audit.json")).read()
        assertEquals(2, entries.size, "被取消的和被放行的各留一条记录")
        assertFalse(entries.first().succeeded, "被取消的请求不该记成成功")
        assertNull(entries.first().status)
        assertTrue(entries.first().sentCharacters > 0)
    }

    @Test
    fun aStopBeforeTheConnectionStartsMeansThePeerNeverSeesAConnect() {
        // Every checkpoint the gateway runs has already passed — the last one is the check after the
        // headers — and the request is held just before the call that starts the connection. A stop
        // here must mean the connection is never started at all. The peer is asserted on the connect
        // attempts it saw, not on the bytes it received: refusing the body after a connection has
        // already been started is exactly what the review found insufficient.
        val pausedBeforeStart = CountDownLatch(1)
        val resumeStart = CountDownLatch(1)
        val call = GatewayCall()
        val peer = FrozenPeer(
            call = call,
            pausedBeforeStart = pausedBeforeStart,
            resumeStart = resumeStart,
        )
        val gateway = OutboundGateway(
            hosts = AllowedHosts("https://api.deepseek.com", allowCleartextLocalhost = false),
            audit = AuditLog(File(directory, "privacy/audit.json")),
            clock = { "2026-10-18T00:00:00Z" },
            open = { peer },
        )
        val outcome = AtomicReference<Result<GatewayResponse>?>(null)
        val worker = thread(name = "gateway-stop-before-connect") {
            outcome.set(
                runCatching {
                    gateway.send(
                        GatewayRequest(
                            url = "https://api.deepseek.com/chat/completions",
                            purpose = GatewayPurpose.RoundtableChat,
                            method = "POST",
                            body = """{"messages":[{"role":"user","content":"这句不该发出去"}]}""",
                            call = call,
                        ),
                    )
                },
            )
        }
        assertTrue(pausedBeforeStart.await(5, TimeUnit.SECONDS), "网关没有停在建连之前")
        call.cancel()
        resumeStart.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive, "取消之后网关没有返回")
        assertEquals(0, peer.connects, "取消之后还是发起了建连：假服务端见了 ${peer.connects} 次")
        assertEquals(0, peer.bytes, "取消之后假服务端收到了正文：${peer.body}")
        val failure = outcome.get()?.exceptionOrNull()
        assertTrue(failure is GatewayCancelled, "期望取消异常，实际是 $failure")
        val entry = AuditLog(File(directory, "privacy/audit.json")).read().single()
        assertFalse(entry.succeeded, "被取消的请求不该记成成功")
        assertNull(entry.status)
        assertTrue(entry.sentCharacters > 0)
    }

    @Test
    fun aStopWhileTheConnectionIsBeingStartedEndsThatConnectAtOnce() {
        // The other branch: the start was already decided and the peer is inside `connect()`, held
        // there the way the network holds a platform connect. Nothing in this test releases it — the
        // exit time is the worker's own, measured from `cancel()` — so the only thing that can end
        // the connect is the stop's disconnect.
        val connectEntered = CountDownLatch(1)
        val exitedAt = AtomicLong(0)
        val call = GatewayCall()
        val peer = FrozenPeer(call = call, connectEntered = connectEntered, holdConnect = true)
        val gateway = OutboundGateway(
            hosts = AllowedHosts("https://api.deepseek.com", allowCleartextLocalhost = false),
            audit = AuditLog(File(directory, "privacy/audit.json")),
            clock = { "2026-10-18T00:00:00Z" },
            open = { peer },
        )
        val outcome = AtomicReference<Result<GatewayResponse>?>(null)
        val worker = thread(name = "gateway-stop-during-connect") {
            outcome.set(
                runCatching {
                    gateway.send(
                        GatewayRequest(
                            url = "https://api.deepseek.com/chat/completions",
                            purpose = GatewayPurpose.RoundtableChat,
                            method = "POST",
                            body = """{"messages":[{"role":"user","content":"这句不该发出去"}]}""",
                            call = call,
                        ),
                    )
                },
            )
            exitedAt.set(System.nanoTime())
        }
        assertTrue(connectEntered.await(5, TimeUnit.SECONDS), "网关没有进到建连")
        val started = System.nanoTime()
        call.cancel()
        worker.join(5_000)
        assertFalse(worker.isAlive, "取消之后正在建立的连接没有被中断")
        val elapsedMs = (exitedAt.get() - started) / 1_000_000
        assertTrue(elapsedMs < 2_000, "取消后等了 ${elapsedMs}ms，建连没有被立刻断开")
        val failure = outcome.get()?.exceptionOrNull()
        assertTrue(failure is GatewayCancelled, "期望取消异常，实际是 $failure")
        assertEquals(1, peer.connects, "应当恰好发起一次建连")
        assertEquals(0, peer.bytes, "取消之后假服务端收到了正文：${peer.body}")
        assertTrue(peer.disconnects >= 1, "取消之后没有断开正在建立的连接")
    }

    @Test
    fun aRequestThatIsNotStoppedGoesThroughTheSameGateAndDeliversTheWholeBody() {
        // Positive control for the two tests above: the same peer wiring with no stop delivers the
        // body in full, so their zeros are the stop and not a peer that swallows everything.
        val connectEntered = CountDownLatch(1)
        val call = GatewayCall()
        val peer = FrozenPeer(call = call, connectEntered = connectEntered)
        val gateway = OutboundGateway(
            hosts = AllowedHosts("https://api.deepseek.com", allowCleartextLocalhost = false),
            audit = AuditLog(File(directory, "privacy/audit.json")),
            clock = { "2026-10-18T00:00:00Z" },
            open = { peer },
        )
        val response = gateway.send(
            GatewayRequest(
                url = "https://api.deepseek.com/chat/completions",
                purpose = GatewayPurpose.RoundtableChat,
                method = "POST",
                body = "正常正文",
                call = call,
            ),
        )
        assertEquals(200, response.status)
        assertEquals(1, peer.connects, "放行的请求没有发起建连")
        assertEquals("正常正文", peer.body, "放行的请求没有把正文交给假服务端")
        val entry = AuditLog(File(directory, "privacy/audit.json")).read().single()
        assertTrue(entry.succeeded)
    }

    private fun gateway(base: String, cleartext: Boolean = false) = OutboundGateway(
        hosts = AllowedHosts(base, cleartext),
        audit = AuditLog(File(directory, "privacy/audit.json")),
        clock = { "2026-10-17T00:00:00Z" },
        open = ::openHttp,
    )
}

/**
 * A controlled stand-in for the service a request talks to. It models the cancellation-relevant
 * behaviour of a real `HttpURLConnection`:
 *
 *  * `getOutputStream()` starts the connection at its end, exactly like the platform implementation,
 *    so `connect()` is the point at which a request really starts going out;
 *  * `connect()` can be held inside the connect ([holdConnect]) and only `disconnect()` ends it: the
 *    held `connect()` throws at once instead of waiting for a timeout, which is what a real
 *    disconnect does to a connection that is still being established;
 *  * a `connect()` after `disconnect()`, or after the request's own switch, throws at once, so a peer
 *    never reports a connection that a stop already tore down;
 *  * [connects] counts connect attempts while [bytes]/[body] count what was received, so a test can
 *    tell "no connection was ever started" apart from "a connection started but no byte went out".
 *
 * [pausedBeforeStart]/[resumeStart] hold the peer just before the connection starts — `setDoOutput`
 * runs after the gateway's last checkpoint and before the start gate — which is the window a stop
 * must win. Nothing here releases a worker on its own: the only way out of [holdConnect] is
 * `disconnect()`.
 */
private class FrozenPeer(
    private val call: GatewayCall? = null,
    private val connectEntered: CountDownLatch = CountDownLatch(0),
    private val holdConnect: Boolean = false,
    private val pausedBeforeStart: CountDownLatch? = null,
    private val resumeStart: CountDownLatch? = null,
) : HttpURLConnection(URI("https://api.deepseek.com/chat/completions").toURL()) {
    private val received = ByteArrayOutputStream()
    private val interrupted = CountDownLatch(1)

    @Volatile
    private var disconnected = false

    @Volatile
    private var established = false

    @Volatile
    var connects: Int = 0
        private set

    @Volatile
    var disconnects: Int = 0
        private set

    val bytes: Int get() = synchronized(received) { received.size() }

    val body: String get() = synchronized(received) { String(received.toByteArray(), Charsets.UTF_8) }

    /** The gateway sets this right before the start gate; the window a stop must win lives here. */
    override fun setDoOutput(doOutput: Boolean) {
        if (doOutput) {
            pausedBeforeStart?.countDown()
            resumeStart?.await(5, TimeUnit.SECONDS)
        }
        super.setDoOutput(doOutput)
    }

    override fun getOutputStream(): OutputStream {
        // A real HttpURLConnection establishes the connection here.
        connect()
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
        disconnected = true
        // This is what ends a connect that is still in progress, exactly as it does for a real one.
        interrupted.countDown()
    }

    override fun usingProxy(): Boolean = false

    override fun connect() {
        if (established) return
        connects += 1
        connectEntered.countDown()
        if (holdConnect) {
            interrupted.await(5, TimeUnit.SECONDS)
            throw IOException("连接在建立过程中被取消")
        }
        if (disconnected || call?.isCancelled == true) throw IOException("连接已被取消")
        established = true
    }
}
