package com.xavierxia.mycelium.hearth

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xavierxia.mycelium.net.AuditEntry
import com.xavierxia.mycelium.net.AuditLog
import com.xavierxia.mycelium.net.HttpReply
import com.xavierxia.mycelium.net.LocalHttp
import com.xavierxia.mycelium.roundtable.CloudSettingsStore
import com.xavierxia.mycelium.roundtable.KeystoreSecretBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2-2 全量审必修第 2、4 条的真机时序复验。
 *
 * 第 2 条：生成期间换会话 / 离开页面，旧的一轮必须真的停下、`busy` 必须被清掉、页面立刻能用，
 * 晚到的回答不能上屏也不能入库。
 * 第 4 条：等云端确认的那一轮必须跟着「人物 + 服务 + 地址 + 模式 + 会话」走。弹框被取消或页面
 * 离开之后，这个确认任务再跑也不能发请求、不能改写确认状态、不能留下测试模式的文字。
 *
 * 两条都不点屏幕：直接驱动 [HearthViewModel]，假服务是进程内的 [LocalHttp]（睡 5 秒模拟慢返回）。
 * 时序用轮询等待表达，所以断言本身就带着「最迟多久必须停下」。
 */
@RunWith(AndroidJUnit4::class)
class HearthNavigationDuringTurnDeviceTest {

    @Test
    fun switchingConversationOrModeWhileGeneratingStopsTheTurnAndFreesThePage() {
        val context = application()
        val settings = CloudSettingsStore(context, KeystoreSecretBox("mycelium-cloud"))
        val database = HearthDatabase(context)
        val characters = CharacterStore { database.session() }
        val log = ConversationLog { database.session() }
        val audit = AuditLog(File(context.filesDir, "privacy/audit.json"))
        sweepEarlierRuns(characters, settings, audit)
        val auditBefore = audit.read()

        val tag = System.currentTimeMillis().toString().takeLast(6)
        val serviceName = "P2-2 导航复验服务 $tag"
        val characterName = "导航复验人物 $tag"
        val question = "导航复验：这句会不会跟着页面走 $tag"
        val lateAnswer = "不该出现的晚到回答 $tag"

        val calls = AtomicInteger(0)
        val server = LocalHttp {
            calls.incrementAndGet()
            Thread.sleep(5_000)
            HttpReply(200, """{"choices":[{"message":{"content":"$lateAnswer"}}]}""")
        }
        val baseUrl = "http://127.0.0.1:${server.port}"

        var serviceId: String? = null
        var characterId: String? = null
        try {
            val service = settings.addService(null, serviceName, baseUrl, "test-key-127")
            serviceId = service
            settings.saveModels(service, listOf("deepseek-test"))
            characterId = characters.create(
                CharacterDraft(
                    name = characterName,
                    personality = "真机复验用的临时人物，测试结束就删掉。",
                    greeting = "在的。",
                    modelKind = ModelKind.CLOUD,
                    serviceId = service,
                    serviceName = serviceName,
                    modelName = "deepseek-test",
                    cloudConfirmedTarget = cloudTarget(service, baseUrl),
                ),
                now(),
            ).id
            val character = requireNotNull(characters.character(requireNotNull(characterId)))
            val model = HearthViewModel(context)

            model.openChat(character)
            waitUntil(20_000) { model.state.value.openCharacter?.id == character.id }

            // 第一轮：生成中「新对话」。
            model.send(question)
            waitUntil(20_000) { model.state.value.busy }
            assertEquals("第一次请求应该已经到达假服务", 1, calls.get())
            model.newConversation()
            waitUntil(5_000) { !model.state.value.busy }
            assertFalse("换会话之后不该还显示生成中", model.state.value.busy)
            assertNull("换会话之后不该还挂着流式内容", model.state.value.streaming)
            assertNull("换会话之后不该留着等待确认的问题", model.state.value.sendConfirm)

            // 旧请求晚到的回答不能上屏、不能入库。
            waitUntil(20_000) { !model.state.value.busy }
            assertFalse(
                "晚到的回答上屏了",
                model.state.value.messages.any { it.text.contains(lateAnswer) },
            )
            assertTrue("晚到的回答入库了", log.conversations(character.id).isEmpty())

            // 页面确实能继续用：再发一次，请求必须真的发出去。
            model.send("$question 第二轮")
            waitUntil(20_000) { calls.get() >= 2 }
            assertEquals("页面应该还能继续发送", 2, calls.get())
            waitUntil(20_000) { model.state.value.busy }

            // 第二轮：生成中切到测试模式。同样必须停下并清干净。
            model.setMode(HearthMode.TEST)
            waitUntil(5_000) { !model.state.value.busy && model.state.value.mode == HearthMode.TEST }
            assertFalse("切模式之后不该还显示生成中", model.state.value.busy)
            assertTrue("测试模式不该有沉浸历史", model.state.value.conversations.isEmpty())
            assertTrue("切模式时不该写库", log.conversations(character.id).isEmpty())
            assertFalse(
                "切模式后晚到的回答上屏了",
                model.state.value.messages.any { it.text.contains(lateAnswer) },
            )
        } finally {
            characterId?.let { characters.delete(it) }
            serviceId?.let { settings.removeService(it) }
            restoreAudit(audit, auditBefore)
            server.close()
        }
    }

    @Test
    fun aConfirmationTheUserLeftBehindCannotSendOrWriteAnything() {
        val context = application()
        val settings = CloudSettingsStore(context, KeystoreSecretBox("mycelium-cloud"))
        val database = HearthDatabase(context)
        val characters = CharacterStore { database.session() }
        val log = ConversationLog { database.session() }
        val audit = AuditLog(File(context.filesDir, "privacy/audit.json"))
        sweepEarlierRuns(characters, settings, audit)
        val auditBefore = audit.read()

        val tag = System.currentTimeMillis().toString().takeLast(6)
        val serviceName = "P2-2 导航复验服务 $tag"
        val characterName = "导航复验人物 $tag"
        val question = "导航复验：这句还会发出去吗 $tag"
        val mustNotArrive = "不该出现的回答 $tag"
        // 人物记的确认目标不是这个服务现在的地址：下一次发送必须先问一次。
        val staleTarget = cloudTarget("另一个服务", "http://127.0.0.1:1")

        val calls = AtomicInteger(0)
        val server = LocalHttp {
            calls.incrementAndGet()
            HttpReply(200, """{"choices":[{"message":{"content":"$mustNotArrive"}}]}""")
        }
        val baseUrl = "http://127.0.0.1:${server.port}"

        var serviceId: String? = null
        var characterId: String? = null
        try {
            val service = settings.addService(null, serviceName, baseUrl, "test-key-127")
            serviceId = service
            settings.saveModels(service, listOf("deepseek-test"))
            characterId = characters.create(
                CharacterDraft(
                    name = characterName,
                    personality = "真机复验用的临时人物，测试结束就删掉。",
                    greeting = "在的。",
                    modelKind = ModelKind.CLOUD,
                    serviceId = service,
                    serviceName = serviceName,
                    modelName = "deepseek-test",
                    cloudConfirmedTarget = staleTarget,
                ),
                now(),
            ).id
            val character = requireNotNull(characters.character(requireNotNull(characterId)))
            val model = HearthViewModel(context)

            model.openChat(character)
            waitUntil(20_000) { model.state.value.openCharacter?.id == character.id }
            model.send(question)
            waitUntil(20_000) { model.state.value.sendConfirm != null }
            assertEquals("弹确认框的时候不该有请求", 0, calls.get())

            // 确认任务先起来，紧接着离开页面：两种先后都不许发出请求。
            model.confirmSend()
            model.leaveHearth()
            Thread.sleep(1_500)

            assertEquals("离开之后这个确认任务不该发出请求", 0, calls.get())
            assertEquals(
                "人物上的确认状态不该被这个任务改写",
                staleTarget,
                requireNotNull(characters.character(character.id)).cloudConfirmedTarget,
            )
            assertTrue("不该建出对话", log.conversations(character.id).isEmpty())
            assertTrue("提问框的内容应该跟着页面一起收走", model.state.value.pendingQuestion == null)
        } finally {
            characterId?.let { characters.delete(it) }
            serviceId?.let { settings.removeService(it) }
            restoreAudit(audit, auditBefore)
            server.close()
        }
    }
}

/** 一直在应用自己的 Application 上跑，人物与对话都进真实（加密）库。 */
private fun application(): Application =
    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

/** 人物和对话的时间戳用应用自己的格式，排序规则才一致。 */
private fun now(): String = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

/** 等一个条件成立；等不到就让断言失败，而不是让测试挂住。 */
private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(50)
    }
    assertTrue("等待超时（${timeoutMs}ms）：条件一直没成立", condition())
}

/** 这一族真机测试自己用的名字前缀和问句，只用来认自己留下的东西。 */
private val leftoverNames = listOf("P2-2 导航复验服务 ", "导航复验人物 ")
private val leftoverQuestions = listOf("导航复验：这句会不会跟着页面走", "导航复验：这句还会发出去吗")

/** 清掉上一次跑挂掉时留下的数据；只认本测试自己的前缀和问句。 */
private fun sweepEarlierRuns(characters: CharacterStore, settings: CloudSettingsStore, audit: AuditLog) {
    settings.services()
        .filter { service -> leftoverNames.any { service.name.startsWith(it) } }
        .forEach { settings.removeService(it.id) }
    characters.characters()
        .filter { character -> leftoverNames.any { character.name.startsWith(it) } }
        .forEach { characters.delete(it.id) }
    val entries = audit.read()
    val kept = entries.filterNot { entry -> leftoverQuestions.any { entry.sentBody.contains(it) } }
    if (kept.size != entries.size) {
        audit.clear()
        kept.forEach { audit.record(it) }
    }
}

/** 把隐私记录恢复到测试开始时的内容：只该清掉测试自己写进去的。 */
private fun restoreAudit(audit: AuditLog, before: List<AuditEntry>) {
    audit.clear()
    before.forEach { audit.record(it) }
}