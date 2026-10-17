package com.xavierxia.mycelium.roundtable

import com.xavierxia.mycelium.contract.AnswerMode
import com.xavierxia.mycelium.contract.ContractFiles
import com.xavierxia.mycelium.contract.SqliteJdbcPackDatabase
import com.xavierxia.mycelium.contract.validateAnswer
import com.xavierxia.mycelium.net.AuditLog
import com.xavierxia.mycelium.net.GatewayPurpose
import com.xavierxia.mycelium.net.HttpReply
import com.xavierxia.mycelium.net.LocalHttp
import com.xavierxia.mycelium.net.AllowedHosts
import com.xavierxia.mycelium.net.OutboundGateway
import com.xavierxia.mycelium.net.openHttp
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.io.path.readText

class CloudChatTest {
    @TempDir
    lateinit var directory: File

    private val files = ContractFiles(repoRoot().resolve("contract"))
    private val template = files.text("answer/system-prompt.zh.txt")
    private val rules = files.text("answer/mode-rules.zh.json")

    @Test
    fun answerVectorsRenderThroughTheSameFunctionTheChatUses() {
        Files.list(files.path("answer/vectors")).use { paths ->
            paths.filter { it.toString().endsWith(".json") }.forEach { path ->
                val vector = JSONObject(path.readText())
                val input = vector.getJSONObject("input")
                val expected = vector.getJSONObject("expected")
                val offered = input.getJSONArray("offered_citation_ids").let { array ->
                    List(array.length()) { array.getString(it) }.toSet()
                }
                val outcome = validateAnswer(input.getString("output_text"), AnswerMode.fromId(input.getString("mode")), offered)
                val shown = outcome.toReply()
                if (expected.getBoolean("accepted")) {
                    val reply = shown as ChatReply.Shown
                    val rendered = if (reply.refusal) null else reply.rendered
                    assertEquals(if (expected.isNull("rendered")) null else expected.getString("rendered"), rendered, vector.getString("id"))
                } else {
                    val reply = shown as ChatReply.Rejected
                    assertEquals("这次回答不合格，没有显示", reply.message)
                    assertTrue(reply.reason.isNotBlank(), vector.getString("id"))
                }
            }
        }
    }

    @Test
    fun normalAnswerIsRenderedAndInvalidAnswerIsNotShown() {
        val valid = """{"statements":[{"text":"先转走定投金额","kind":"evidence","citation_ids":["C1"]}],"refusal_reason":null}"""
        scripted(200, completion(valid)).use { server ->
            val reply = chat(server).ask("记账", AnswerMode.ThoughtInference, settings(server), emptyList()) as ChatReply.Shown
            assertTrue(reply.rendered.contains("先转走定投金额"))
            assertEquals("DeepSeek", reply.provider)
            assertTrue(reply.rendered.contains("[C"))
        }
        scripted(200, completion("not json")).use { server ->
            val reply = chat(server).ask("记账", AnswerMode.ThoughtInference, settings(server), emptyList()) as ChatReply.Rejected
            assertEquals("这次回答不合格，没有显示", reply.message)
            assertFalse(reply.reason.contains("not json"))
        }
    }

    @Test
    fun unauthorizedAndRateLimitedAreReportedWithoutSendingTheKeyToTheLog() {
        scripted(401, """{"error":"no"}""").use { server ->
            val error = assertThrows(IllegalStateException::class.java) {
                chat(server).ask("记账", AnswerMode.ThoughtInference, settings(server), emptyList())
            }
            assertTrue(error.message!!.contains("401"))
            assertFalse(auditText().contains("sk-test"))
        }
        scripted(429, """{"error":"slow"}""").use { server ->
            val error = assertThrows(IllegalStateException::class.java) {
                chat(server).ask("记账", AnswerMode.ThoughtInference, settings(server), emptyList())
            }
            assertTrue(error.message!!.contains("429"))
        }
    }

    @Test
    fun timeoutIsAuditedAndDoesNotReturnAnAnswer() {
        LocalHttp { Thread.sleep(1_500); HttpReply(200, completion("{}")) }.use { server ->
            val error = assertThrows(Exception::class.java) {
                CloudChat(DeepSeekAnswerModel(gateway(server), readTimeoutMs = 200), ::openSample, template, rules) { 0.0 }
                    .ask("记账", AnswerMode.ThoughtInference, settings(server), emptyList())
            }
            assertFalse(error.message.orEmpty().contains("sk-test"))
            val entry = AuditLog(File(directory, "audit.json")).read().single()
            assertEquals(GatewayPurpose.RoundtableChat, entry.purpose)
            assertFalse(entry.succeeded)
        }
    }

    @Test
    fun overBudgetDoesNotSend() {
        scripted(200, completion("{}")).use { server ->
            val before = server.count.get()
            assertThrows(BudgetExceeded::class.java) {
                CloudChat(DeepSeekAnswerModel(gateway(server)), ::openSample, template, rules) { 5.0 }
                    .ask("记账", AnswerMode.ThoughtInference, settings(server).copy(monthlyLimitUsd = 5.0), emptyList())
            }
            assertEquals(before, server.count.get())
        }
    }

    @Test
    fun conversationKeepsItsVersionAndDeletesCleanly() {
        val session = MemorySession()
        val store = ConversationStore { session }
        val id = store.append("luchuan", "ver-1", null, "怎么记账", "先记下", "[]", "DeepSeek", "deepseek-flash", "t1")
        store.append("luchuan", "ver-2", id, "还有呢", "继续记", "[]", "DeepSeek", "deepseek-flash", "t2")
        assertEquals("ver-1", store.versionId(id))
        assertEquals(4, store.messages(id).size)
        store.delete(id)
        assertTrue(store.messages(id).isEmpty())
        assertTrue(store.conversations("luchuan").isEmpty())
    }

    private fun chat(server: LocalHttp) = CloudChat(
        DeepSeekAnswerModel(gateway(server)),
        ::openSample,
        template,
        rules,
    ) { 0.0 }

    private fun gateway(server: LocalHttp) = OutboundGateway(
        hosts = AllowedHosts("http://127.0.0.1:${server.port}", true),
        audit = AuditLog(File(directory, "audit.json")),
        clock = { "2026-10-17T00:00:00Z" },
        open = ::openHttp,
        costOf = { _, response ->
            val usage = JSONObject(response.body).optJSONObject("usage") ?: return@OutboundGateway 0.0
            usage.optInt("prompt_tokens") * 0.15 / 1_000_000.0 + usage.optInt("completion_tokens") * 0.6 / 1_000_000.0
        },
    )

    private fun settings(server: LocalHttp) = ModelSettings(
        baseUrl = "http://127.0.0.1:${server.port}",
        model = "deepseek-flash",
        prices = ModelPrices(0.15, 0.6),
        monthlyLimitUsd = 5.0,
        apiKey = "sk-test",
    )

    private fun openSample() = SqliteJdbcPackDatabase(files.sampleDatabase().toString())

    private fun scripted(status: Int, body: String) = LocalHttp { HttpReply(status, body) }

    private fun completion(content: String) =
        """{"choices":[{"message":{"content":${JSONObject.quote(content)}}}],"usage":{"prompt_tokens":20,"completion_tokens":8}}"""

    private fun auditText() = File(directory, "audit.json").takeIf { it.isFile }?.readText().orEmpty()

    private fun repoRoot() = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }
        .first { it.resolve("contract/answer/vectors").toFile().isDirectory }
}

private fun com.xavierxia.mycelium.contract.AnswerOutcome.toReply(): ChatReply = when (this) {
    is com.xavierxia.mycelium.contract.AnswerOutcome.Rejected -> ChatReply.Rejected("这次回答不合格，没有显示", message)
    is com.xavierxia.mycelium.contract.AnswerOutcome.Accepted -> ChatReply.Shown(rendered, refusalReason != null, emptyList(), "DeepSeek", "deepseek-flash", 0.0)
}
