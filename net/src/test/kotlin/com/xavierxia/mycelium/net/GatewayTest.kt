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
        // Guarantee A, the earliest case: the switch outlives the connection, so cancelling before
        // the gateway is even called stops the request there, not after a socket that would have gone
        // out anyway.
        //
        // Proves: no connection object is opened at all, and the request ends as a cancellation with
        // the audit entry its rules require.
        // Does not prove: anything about a connect that has already been started — no connect is
        // reached here, so nothing here speaks to whether Android can interrupt one.
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
        //
        // Proves: part of guarantee B — not one body byte reaches the peer, and the request ends as a
        // cancellation. The peer is a real loopback server here, so "zero bytes" is measured, not
        // asserted on a mock.
        // Does not prove: that the request stopped *early*. The gateway stops it at its own next
        // checkpoint; the connection start is never reached, so this says nothing about interrupting
        // one that has begun.
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
        //
        // Proves: guarantee A at the latest point it can be won — the stop takes the same lock the
        // start decision takes, so the platform call is never invoked. Nothing here depends on the
        // peer being cooperative: [FrozenPeer] does not read the switch, and it is the gateway's gate
        // that keeps [FrozenPeer.connects] at zero.
        // Does not prove: anything about the moment *after* the gate. If the stop loses that race the
        // connection starts, and this test says nothing about that branch.
        val pausedBeforeStart = CountDownLatch(1)
        val resumeStart = CountDownLatch(1)
        val call = GatewayCall()
        val peer = FrozenPeer(
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
        //
        // Proves: the gateway, on a stop, calls `disconnect()` on the connection it registered and
        // returns promptly, and the peer receives no body byte because the write checkpoints run
        // before every write. The held connect is released by that disconnect and by nothing else.
        // Does not prove: that **Android** interrupts a connect in flight. [FrozenPeer] models the
        // intent of `disconnect()` — its `connect()` returns as soon as it is called — which is an
        // assumption about the platform, not an observation of it; the real call is ended, if at all,
        // by the engine closing the socket behind it, and no JVM test can see that. A real device may
        // take until the connect timeout. This is the residual documented for the review, not a fixed
        // defect, and this test must not be read as closing it.
        val connectEntered = CountDownLatch(1)
        val exitedAt = AtomicLong(0)
        val call = GatewayCall()
        val peer = FrozenPeer(connectEntered = connectEntered, holdConnect = true)
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
        //
        // Proves: the fixture would report a connect and a full body when nothing stops it, so the
        // zeroes above are attributable to the stop.
        // Does not prove: anything about cancellation itself.
        val connectEntered = CountDownLatch(1)
        val call = GatewayCall()
        val peer = FrozenPeer(connectEntered = connectEntered)
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

    @Test
    fun theGatewayAlwaysSetsFiniteTimeoutsOnTheConnectionItUses() {
        // The fallback that bounds the one window the gateway cannot close. A stop that lands while
        // the platform call is establishing the connection waits for that call; the connect timeout
        // is the only thing that ends the wait, and 0 there means "no limit at all" (measured on this
        // machine: a freshly opened connection reads 0). Both values are pinned here so that going
        // back to an unbounded connect has to be a deliberate, reviewed change rather than a slip.
        //
        // Proves: the values the gateway installs on the real connection, as the socket sees them.
        // Does not prove: that a real device cancels any faster — a bound is not an interruption.
        val seen = AtomicReference<HttpURLConnection?>(null)
        LocalHttp { HttpReply(200, """{"choices":[{"message":{"content":"{}"}}]}""") }.use { server ->
            val gateway = OutboundGateway(
                hosts = AllowedHosts("http://127.0.0.1:${server.port}", allowCleartextLocalhost = true),
                audit = AuditLog(File(directory, "privacy/audit.json")),
                clock = { "2026-10-18T00:00:00Z" },
                open = { uri -> openHttp(uri).also { seen.set(it) } },
            )
            gateway.send(
                GatewayRequest(
                    url = "http://127.0.0.1:${server.port}/chat/completions",
                    purpose = GatewayPurpose.RoundtableChat,
                    method = "POST",
                    body = "{}",
                ),
            )
        }
        val connection = seen.get()!!
        assertEquals(GatewayRequest.DEFAULT_CONNECT_TIMEOUT_MS, connection.connectTimeout)
        assertEquals(GatewayRequest.DEFAULT_READ_TIMEOUT_MS, connection.readTimeout)
        assertTrue(connection.connectTimeout in 1..GatewayRequest.MAX_CONNECT_TIMEOUT_MS, "建连超时必须有界且不超过上限")
        assertTrue(GatewayRequest.DEFAULT_CONNECT_TIMEOUT_MS <= GatewayRequest.MAX_CONNECT_TIMEOUT_MS)
    }

    @Test
    fun aTimeoutThatMeansInfiniteOnThePlatformIsReplacedByADeclaredBound() {
        // 0 and negative values mean "wait forever" to HttpURLConnection, and a forever connect is a
        // forever wait for a stop that arrived just after the start gate. However a caller fills the
        // request in, it cannot ask for that; the ceiling keeps the opposite mistake bounded too.
        //
        // Proves: what actually reaches the socket for 0, for a negative value and for an absurdly
        // large one.
        // Does not prove: anything about cancellation timing.
        val opened = mutableListOf<HttpURLConnection>()
        LocalHttp { HttpReply(200, "{}") }.use { server ->
            val gateway = OutboundGateway(
                hosts = AllowedHosts("http://127.0.0.1:${server.port}", allowCleartextLocalhost = true),
                audit = AuditLog(File(directory, "privacy/audit.json")),
                clock = { "2026-10-18T00:00:00Z" },
                open = { uri -> openHttp(uri).also { opened += it } },
            )
            listOf(0 to 0, -1 to -1, 600_000 to 600_000).forEach { (connect, read) ->
                gateway.send(
                    GatewayRequest(
                        url = "http://127.0.0.1:${server.port}/models",
                        purpose = GatewayPurpose.FetchModels,
                        connectTimeoutMs = connect,
                        readTimeoutMs = read,
                    ),
                )
            }
        }
        val effective = opened.map { it.connectTimeout to it.readTimeout }
        assertEquals(3, effective.size)
        assertTrue(effective.all { (connect, read) -> connect > 0 && read > 0 }, "无限等待漏到了 socket：$effective")
        assertEquals(
            listOf(
                GatewayRequest.DEFAULT_CONNECT_TIMEOUT_MS to GatewayRequest.DEFAULT_READ_TIMEOUT_MS,
                GatewayRequest.DEFAULT_CONNECT_TIMEOUT_MS to GatewayRequest.DEFAULT_READ_TIMEOUT_MS,
                GatewayRequest.MAX_CONNECT_TIMEOUT_MS to 600_000,
            ),
            effective,
        )
    }

    private fun gateway(base: String, cleartext: Boolean = false) = OutboundGateway(
        hosts = AllowedHosts(base, cleartext),
        audit = AuditLog(File(directory, "privacy/audit.json")),
        clock = { "2026-10-17T00:00:00Z" },
        open = ::openHttp,
    )
}

/**
 * A controlled stand-in for the service a request talks to. It models the parts of a real
 * `HttpURLConnection` that the gateway's own guarantees rest on, and nothing else:
 *
 *  * `getOutputStream()` starts the connection at its end, exactly like the platform
 *    implementation — measured on this machine for the reference implementation, and the same in
 *    Android's — so `connect()` is the point at which a request really starts going out.
 *  * [connects] counts connect attempts while [bytes]/[body] count what was received, so a test can
 *    tell "no connection was ever started" apart from "a connection started but no byte went out".
 *  * [pausedBeforeStart]/[resumeStart] hold the peer just before the connection starts —
 *    `setDoOutput` runs after the gateway's last checkpoint and before the start gate — which is the
 *    window a stop must win.
 *  * [holdConnect] keeps an already started `connect()` inside the call, and only `disconnect()`
 *    releases it. This is an **assumption about the platform, not a verified fact**: on Android the
 *    in-flight call ends because `disconnect()` closes the socket behind it, which is possible once
 *    the engine exists, but nothing in a JVM test can observe that. The fixture models the intent of
 *    `disconnect()` at whatever moment the test chooses; a real device may arrive at the same
 *    outcome later, or not before its timeout.
 *
 * What this fixture deliberately does **not** model, because the platform does not have it, is any
 * persistent cancellation state the connection could read. It never asks the request's
 * [GatewayCall] whether it was cancelled, and it reproduces the platform's no-op: a `disconnect()`
 * that arrives while nothing has been started does not stop a later `connect()`, which proceeds
 * normally. That is exactly the residual window the gateway cannot close. So the coverage of "no
 * connection is started" and "no body byte is written" comes from the gateway's own gate and
 * checkpoints, not from a cooperative peer — which is why those tests say something about the
 * gateway and nothing at all about whether Android can interrupt a connect in flight.
 */
private class FrozenPeer(
    private val connectEntered: CountDownLatch = CountDownLatch(0),
    private val holdConnect: Boolean = false,
    private val pausedBeforeStart: CountDownLatch? = null,
    private val resumeStart: CountDownLatch? = null,
) : HttpURLConnection(URI("https://api.deepseek.com/chat/completions").toURL()) {
    private val received = ByteArrayOutputStream()
    private val interrupted = CountDownLatch(1)

    @Volatile
    private var established = false

    /**
     * True only while `connect()` is inside this fixture. A stop can end a connect only when one has
     * actually been started — with no engine and no socket yet there is nothing to close, which is
     * the platform limitation reproduced here.
     */
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
        // Only a connect that is already in progress can be ended by this, the way closing the
        // socket ends one. Nothing else here remembers the disconnect: the platform's disconnect()
        // is a no-op while no engine exists, and a later connect() starts normally.
        if (connectInFlight) interrupted.countDown()
    }

    override fun usingProxy(): Boolean = false

    override fun connect() {
        if (established) return
        connects += 1
        // Set before the test is told the connect was entered, so a stop that arrives the moment the
        // latch opens still finds the connect in flight.
        connectInFlight = true
        connectEntered.countDown()
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
