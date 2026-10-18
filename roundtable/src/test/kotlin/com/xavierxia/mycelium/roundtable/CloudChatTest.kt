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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.sql.DriverManager
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
            val reply = runBlocking { chat(server).ask("记账", AnswerMode.ThoughtInference, emptyList()) } as ChatReply.Shown
            assertTrue(reply.rendered.contains("先转走定投金额"))
            assertEquals("假服务", reply.provider)
            assertTrue(reply.rendered.contains("[C"))
        }
        scripted(200, completion("not json")).use { server ->
            val reply = runBlocking { chat(server).ask("记账", AnswerMode.ThoughtInference, emptyList()) } as ChatReply.Rejected
            assertEquals("这次回答不合格，没有显示", reply.message)
            assertFalse(reply.reason.contains("not json"))
        }
    }

    @Test
    fun unauthorizedAndRateLimitedAreReportedWithoutSendingTheKeyToTheLog() {
        scripted(401, """{"error":"no"}""").use { server ->
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { chat(server).ask("记账", AnswerMode.ThoughtInference, emptyList()) }
            }
            assertTrue(error.message!!.contains("401"))
            assertFalse(auditText().contains("sk-test"))
        }
        scripted(429, """{"error":"slow"}""").use { server ->
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { chat(server).ask("记账", AnswerMode.ThoughtInference, emptyList()) }
            }
            assertTrue(error.message!!.contains("429"))
        }
    }

    @Test
    fun timeoutIsAuditedAndDoesNotReturnAnAnswer() {
        LocalHttp { Thread.sleep(1_500); HttpReply(200, completion("{}")) }.use { server ->
            val error = assertThrows(Exception::class.java) {
                runBlocking {
                    chat(server, readTimeoutMs = 200).ask("记账", AnswerMode.ThoughtInference, emptyList())
                }
            }
            assertTrue(
                error is SocketTimeoutException || error.cause is SocketTimeoutException,
                "expected a timeout, got ${error::class.java.name}",
            )
            val entry = AuditLog(File(directory, "audit.json")).read().single()
            assertEquals(GatewayPurpose.RoundtableChat, entry.purpose)
            assertFalse(entry.succeeded)
        }
    }

    @Test
    fun manyAnswersInARowAreNeverBlocked() {
        scripted(200, completion("""{"statements":[{"text":"先转走定投金额","kind":"evidence","citation_ids":["C1"]}],"refusal_reason":null}""")).use { server ->
            repeat(3) {
                val reply = runBlocking { chat(server).ask("记账", AnswerMode.ThoughtInference, emptyList()) }
                assertTrue(reply is ChatReply.Shown)
            }
            assertEquals(3, server.count.get())
        }
    }

    @Test
    fun modelListIsPulledThroughTheGatewayAndNeverLoggedWithTheKey() {
        LocalHttp { call ->
            assertEquals("/models", call.path)
            assertEquals("Bearer sk-test", call.headers["authorization"])
            HttpReply(200, """{"data":[{"id":"deepseek-flash"},{"id":"deepseek-pro"}]}""")
        }.use { server ->
            val models = pullModels(gateway(server), "http://127.0.0.1:${server.port}", "sk-test")
            assertEquals(listOf("deepseek-flash", "deepseek-pro"), models)
            val entry = AuditLog(File(directory, "audit.json")).read().single()
            assertEquals(GatewayPurpose.FetchModels, entry.purpose)
            assertFalse(auditText().contains("sk-test"))
        }
    }

    @Test
    fun emptyModelListComesBackEmptyWithoutAnError() {
        scripted(200, """{"data":[]}""").use { server ->
            assertTrue(pullModels(gateway(server), "http://127.0.0.1:${server.port}", "sk-test").isEmpty())
        }
    }

    @Test
    fun modelListFailuresCarryChineseMessages() {
        scripted(401, """{"error":"no"}""").use { server ->
            val error = assertThrows(ModelListError::class.java) {
                pullModels(gateway(server), "http://127.0.0.1:${server.port}", "sk-test")
            }
            assertTrue(error.message!!.contains("401"))
        }
        LocalHttp { Thread.sleep(1_500); HttpReply(200, """{"data":[]}""") }.use { server ->
            val error = assertThrows(ModelListError::class.java) {
                pullModels(gateway(server), "http://127.0.0.1:${server.port}", "sk-test", readTimeoutMs = 200)
            }
            assertTrue(error.message!!.contains("没有在时间内响应"), error.message!!)
        }
        scripted(200, "not json at all").use { server ->
            val error = assertThrows(ModelListError::class.java) {
                pullModels(gateway(server), "http://127.0.0.1:${server.port}", "sk-test")
            }
            assertTrue(error.message!!.contains("格式不认识"), error.message!!)
        }
        scripted(200, """{"data":[{"name":"missing-id"}]}""").use { server ->
            val error = assertThrows(ModelListError::class.java) {
                pullModels(gateway(server), "http://127.0.0.1:${server.port}", "sk-test")
            }
            assertTrue(error.message!!.contains("格式不认识"), error.message!!)
        }
    }

    @Test
    fun aFailedPullLeavesSavedModelsUntouched() {
        // The rule the settings screen relies on: a failed pull never reaches saveModels.
        var blob: String? = null
        val cloud = CloudStore({ blob }, { blob = it }, object : SecretBox {
            override fun wrap(plain: ByteArray): String = String(plain, Charsets.UTF_8)
            override fun unwrap(stored: String): ByteArray = stored.toByteArray(Charsets.UTF_8)
        })
        val serviceId = cloud.addService(null, "假服务", "http://127.0.0.1:1", "sk-test")
        cloud.saveModels(serviceId, listOf("deepseek-flash"))

        scripted(401, """{"error":"no"}""").use { server ->
            assertThrows(ModelListError::class.java) {
                pullModels(gateway(server), "http://127.0.0.1:${server.port}", "sk-test")
            }
        }
        assertEquals(listOf("deepseek-flash"), cloud.models(serviceId))
        assertEquals(1, cloud.services().size)
    }

    @Test
    fun conversationKeepsItsVersionAndDeletesCleanly() {
        val session = MemorySession()
        val store = ConversationStore { session }
        val id = store.append("luchuan", "ver-1", null, "怎么记账", "先记下", "[]", "假服务", "deepseek-flash", "t1")
        store.append("luchuan", "ver-2", id, "还有呢", "继续记", "[]", "假服务", "deepseek-flash", "t2")
        assertEquals("ver-1", store.versionId(id))
        assertEquals(4, store.messages(id).size)
        store.delete(id)
        assertTrue(store.messages(id).isEmpty())
        assertTrue(store.conversations("luchuan").isEmpty())
    }

    /**
     * The device test searches the SQLCipher file for a sentence in byte space. This proves the
     * same comparison actually hits when a file is NOT encrypted, so the device assertion can fail.
     */
    @Test
    fun theByteSpaceSearchUsedOnTheDeviceFindsAnUnencryptedSentence() {
        val file = File(directory, "plain.sqlite").apply { delete() }
        val sentence = "明文对话句子不应出现在库文件里"
        val connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")
        try {
            val session = object : SqlSession {
                override fun exec(sql: String, args: List<Any?>) {
                    if (args.isEmpty()) {
                        connection.createStatement().use { it.execute(sql) }
                    } else {
                        connection.prepareStatement(sql).use { statement ->
                            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                            statement.executeUpdate()
                        }
                    }
                }

                override fun query(sql: String, args: List<Any?>): List<List<Any?>> = emptyList()

                override fun close() = Unit
            }
            ConversationStore { session }.append(
                "luchuan", "ver-1", null, sentence, "回答也不应出现", "[]", "假服务", "deepseek-flash", "t",
            )
        } finally {
            connection.close()
        }

        val latin = String(file.readBytes(), Charsets.ISO_8859_1)
        assertTrue(latin.startsWith("SQLite format 3"))
        assertTrue(latin.contains(String(sentence.toByteArray(), Charsets.ISO_8859_1)))
        assertTrue(latin.contains(String("回答也不应出现".toByteArray(), Charsets.ISO_8859_1)))
    }

    private fun chat(server: LocalHttp, readTimeoutMs: Int = 60_000) = CloudChat(
        model = CloudChatModel(
            gateway = gateway(server),
            serviceName = "假服务",
            baseUrl = "http://127.0.0.1:${server.port}",
            apiKey = "sk-test",
            model = "deepseek-flash",
            readTimeoutMs = readTimeoutMs,
        ),
        providerName = "假服务",
        modelName = "deepseek-flash",
        openPack = ::openSample,
        template = template,
        rulesJson = rules,
    )

    private fun gateway(server: LocalHttp) = OutboundGateway(
        hosts = AllowedHosts("http://127.0.0.1:${server.port}", true),
        audit = AuditLog(File(directory, "audit.json")),
        clock = { "2026-10-17T00:00:00Z" },
        open = ::openHttp,
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
    is com.xavierxia.mycelium.contract.AnswerOutcome.Accepted -> ChatReply.Shown(rendered, refusalReason != null, emptyList(), "假服务", "deepseek-flash")
}
