package com.xavierxia.mycelium.roundtable

import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.ChatRole
import com.xavierxia.mycelium.model.GenerationParameters
import com.xavierxia.mycelium.net.AllowedHosts
import com.xavierxia.mycelium.net.AuditLog
import com.xavierxia.mycelium.net.GatewayPurpose
import com.xavierxia.mycelium.net.HttpReply
import com.xavierxia.mycelium.net.LocalHttp
import com.xavierxia.mycelium.net.OutboundGateway
import com.xavierxia.mycelium.net.openHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The hearth space reuses this cloud model. What matters here is that the purpose a caller picks
 * reaches the gateway, because the purpose decides what the audit log may write down.
 */
class HearthCloudModelTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun theTestModePurposeReachesTheAuditWithoutTheText() {
        LocalHttp { HttpReply(200, """{"choices":[{"message":{"content":"喵"}}]}""") }.use { server ->
            val answer = runBlocking {
                model(server, GatewayPurpose.HearthChatTest)
                    .generate(listOf(ChatMessage(ChatRole.USER, "我今天很难过")), GenerationParameters())
                    .toList()
                    .joinToString("")
            }
            assertEquals("喵", answer)
        }
        val entry = AuditLog(File(directory, "audit.json")).read().single()
        assertEquals(GatewayPurpose.HearthChatTest, entry.purpose)
        assertEquals("", entry.sentBody)
        assertTrue(entry.sentCharacters > 0)
        assertFalse(File(directory, "audit.json").readText().contains("难过"))
    }

    @Test
    fun theImmersivePurposeKeepsTheText() {
        LocalHttp { HttpReply(200, """{"choices":[{"message":{"content":"在的"}}]}""") }.use { server ->
            runBlocking {
                model(server, GatewayPurpose.HearthChat)
                    .generate(listOf(ChatMessage(ChatRole.USER, "我今天很难过")), GenerationParameters())
                    .toList()
            }
        }
        val entry = AuditLog(File(directory, "audit.json")).read().single()
        assertEquals(GatewayPurpose.HearthChat, entry.purpose)
        assertTrue(entry.sentBody.contains("难过"))
    }

    @Test
    fun stoppingWhileTheRequestIsInFlightLeavesNoAnswer() {
        // The service takes its time; the user presses stop; nothing may arrive afterwards.
        val inFlight = java.util.concurrent.CountDownLatch(1)
        val received = java.util.concurrent.atomic.AtomicBoolean(false)
        LocalHttp {
            received.set(true)
            inFlight.countDown()
            Thread.sleep(2_000)
            HttpReply(200, """{"choices":[{"message":{"content":"晚到的回答"}}]}""")
        }.use { server ->
            val model = model(server, GatewayPurpose.HearthChat)
            val answers = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())
            runBlocking {
                val job = launch(Dispatchers.IO) {
                    answers.set(
                        model.generate(listOf(ChatMessage(ChatRole.USER, "在吗")), GenerationParameters()).toList(),
                    )
                }
                assertTrue(inFlight.await(5, java.util.concurrent.TimeUnit.SECONDS), "请求没有发出去")
                model.stop()
                job.join()
            }
            assertTrue(received.get(), "假服务应该真的收到过请求")
            assertEquals(emptyList<String>(), answers.get(), "停止之后不该有回答")
        }
    }

    @Test
    fun stoppingBeforeTheRequestStartsSendsNothing() {
        LocalHttp { HttpReply(200, """{"choices":[{"message":{"content":"不该出现"}}]}""") }.use { server ->
            val model = model(server, GatewayPurpose.HearthChat)
            model.stop()
            val answers = runBlocking {
                model.generate(listOf(ChatMessage(ChatRole.USER, "在吗")), GenerationParameters()).toList()
            }
            assertEquals(emptyList<String>(), answers)
            assertEquals(0, server.count.get(), "已经停止的请求不该发出去")
        }
    }

    @Test
    fun stoppingMidFlightReturnsAtOnceInsteadOfWaitingForTheService() {
        // The service needs five seconds; a stop must not wait for it, which only happens if the
        // connection is really closed.
        val inFlight = java.util.concurrent.CountDownLatch(1)
        LocalHttp {
            inFlight.countDown()
            Thread.sleep(5_000)
            HttpReply(200, """{"choices":[{"message":{"content":"晚到的回答"}}]}""")
        }.use { server ->
            val model = model(server, GatewayPurpose.HearthChat)
            runBlocking {
                val job = launch(Dispatchers.IO) {
                    model.generate(listOf(ChatMessage(ChatRole.USER, "在吗")), GenerationParameters()).toList()
                }
                assertTrue(inFlight.await(5, java.util.concurrent.TimeUnit.SECONDS), "请求没有发出去")
                val started = System.nanoTime()
                model.stop()
                job.join()
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue(elapsedMs < 2_000, "停止后等了 ${elapsedMs}ms，说明请求没有被断开")
            }
        }
    }

    @Test
    fun theHearthProtocolSendsNaturalLanguageParameters() {
        LocalHttp { HttpReply(200, """{"choices":[{"message":{"content":"在的"}}]}""") }.use { server ->
            runBlocking {
                naturalModel(server, GatewayPurpose.HearthChat)
                    .generate(listOf(ChatMessage(ChatRole.USER, "在吗")), GenerationParameters(maxTokens = 128, temperature = 0.3f))
                    .toList()
            }
            val body = server.calls().single().body
            // A character talks: no JSON-object answer format, but its own generation parameters.
            assertFalse(body.contains("response_format"), "炉边不该要求 JSON 回答：$body")
            assertTrue(body.contains("\"max_tokens\":128"), "生成长度没有传下去：$body")
            assertTrue(body.contains("\"temperature\":0.3"), "温度没有传下去：$body")
            assertTrue(body.contains("\"top_p\""), "top_p 没有传下去：$body")
        }
    }

    private fun naturalModel(server: LocalHttp, purpose: GatewayPurpose) = CloudChatModel(
        gateway = OutboundGateway(
            hosts = AllowedHosts("http://127.0.0.1:${server.port}", true),
            audit = AuditLog(File(directory, "audit.json")),
            clock = { "2026-10-18T00:00:00Z" },
            open = ::openHttp,
        ),
        serviceName = "假服务",
        baseUrl = "http://127.0.0.1:${server.port}",
        apiKey = "sk-test",
        model = "deepseek-flash",
        purpose = purpose,
        naturalChat = true,
    )

    private fun model(server: LocalHttp, purpose: GatewayPurpose) = CloudChatModel(
        gateway = OutboundGateway(
            hosts = AllowedHosts("http://127.0.0.1:${server.port}", true),
            audit = AuditLog(File(directory, "audit.json")),
            clock = { "2026-10-18T00:00:00Z" },
            open = ::openHttp,
        ),
        serviceName = "假服务",
        baseUrl = "http://127.0.0.1:${server.port}",
        apiKey = "sk-test",
        model = "deepseek-flash",
        purpose = purpose,
    )
}
