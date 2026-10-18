package com.xavierxia.mycelium.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    private fun gateway(base: String, cleartext: Boolean = false) = OutboundGateway(
        hosts = AllowedHosts(base, cleartext),
        audit = AuditLog(File(directory, "privacy/audit.json")),
        clock = { "2026-10-17T00:00:00Z" },
        open = ::openHttp,
    )
}
