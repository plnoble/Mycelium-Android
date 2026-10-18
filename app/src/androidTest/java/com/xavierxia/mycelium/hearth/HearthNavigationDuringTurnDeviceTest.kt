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

    /**
     * 第 4 条：确认任务在**数据库打开/等锁**的时候被取消或离开页面，它再跑也不许发出请求，更不许
     * 改写人物的确认状态，也不许用旧快照把后来编辑的字段覆盖回去。
     *
     * 判别力靠两件事摆出来：进测试模式（这一轮不留记录，写库只可能来自确认本身）；用
     * [GatedHearthSessions] 把确认写事务（同一个会话上的第 2 个事务，1 是 schema 检查）停在
     * `BEGIN IMMEDIATE` 之前，再在停住的时候取消弹窗 / 离页，并顺手改一次人物名字。旧写法会在放行
     * 之后整条快照 update：确认被写上、名字被改回去；新写法在事务内判定有效性，一列都不改。
     */
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
        val secondQuestion = "导航复验：离页之后这句还会发出去吗 $tag"
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
        var hold: HearthStageHold? = null
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
            val gates = GatedHearthSessions(database)
            val model = HearthViewModel(context, gates::open)

            model.openChat(character)
            waitUntil(20_000) { !model.state.value.historyLoading }
            // 进测试模式：这一轮不留记录，库里之后出现的任何变化都只可能来自确认任务本身。
            model.setMode(HearthMode.TEST)
            waitUntil(20_000) { model.state.value.mode == HearthMode.TEST }
            model.send(question)
            waitUntil(20_000) { model.state.value.sendConfirm != null }
            assertEquals("弹确认框的时候不该有请求", 0, calls.get())

            // 场景一：确认任务卡在数据库写事务上时取消弹窗，并在同一时刻改一次人物名字。
            hold = gates.hold(HearthStage.Transaction(2))
            model.confirmSend()
            hold.await()
            model.dismissSendConfirm()
            val editedName = "导航复验人物（改过名字）$tag"
            characters.update(character.id, draftOf(character).copy(name = editedName), now())
            hold.letGo()
            hold = null
            Thread.sleep(1_500)

            assertEquals("取消弹窗之后这个确认任务不该发出请求", 0, calls.get())
            val afterDismiss = requireNotNull(characters.character(character.id))
            assertEquals("人物上的确认状态不该被这个任务改写", staleTarget, afterDismiss.cloudConfirmedTarget)
            assertEquals("停住期间编辑的名字不该被旧快照覆盖回去", editedName, afterDismiss.name)
            assertNull("取消之后不该还挂着这个确认框", model.state.value.sendConfirm)
            assertTrue("不该建出对话", log.conversations(character.id).isEmpty())

            // 场景二：再来一次，这次在同一个窗口里直接离开页面。
            model.send(secondQuestion)
            waitUntil(20_000) { model.state.value.sendConfirm != null }
            hold = gates.hold(HearthStage.Transaction(2))
            model.confirmSend()
            hold.await()
            model.leaveHearth()
            hold.letGo()
            hold = null
            Thread.sleep(1_500)

            assertEquals("离开页面之后这个确认任务不该发出请求", 0, calls.get())
            val afterLeave = requireNotNull(characters.character(character.id))
            assertEquals("确认状态仍然不该被改写", staleTarget, afterLeave.cloudConfirmedTarget)
            assertEquals("改过的名字仍然应该保留", editedName, afterLeave.name)
            assertTrue("两轮都不该建出对话", log.conversations(character.id).isEmpty())
            assertTrue("提问框的内容应该跟着页面一起收走", model.state.value.pendingQuestion == null)
        } finally {
            hold?.letGo()
            characterId?.let { characters.delete(it) }
            serviceId?.let { settings.removeService(it) }
            restoreAudit(audit, auditBefore)
            server.close()
        }
    }

    /**
     * 第 2 条（切历史）：切到另一段对话、它的历史还在读的时候发送。目标必须在切换开始时就已经
     * 定到新的一段，读取期间发送一律拒绝；读取结果被丢弃之后，同一句话要落在刚打开的那一段，
     * 不能发给、也不能存进上一段对话。
     *
     * 时序用 [GatedHearthSessions] 把那次「读消息」停住来摆，不靠 sleep 去碰窗口。
     */
    @Test
    fun aQuestionSentWhileAnotherConversationIsStillLoadingIsRefusedAndNeverRedirected() {
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
        val olderQuestion = "切历史复验：旧对话里的问题 $tag"
        val olderAnswer = "切历史复验：旧对话里的回答 $tag"
        val newestQuestion = "切历史复验：默认打开的那段 $tag"
        val refused = "切历史复验：读取期间这条不该发出去 $tag"
        val accepted = "切历史复验：历史就绪后这条该进刚打开的对话 $tag"

        val calls = AtomicInteger(0)
        val server = LocalHttp {
            calls.incrementAndGet()
            HttpReply(200, """{"choices":[{"message":{"content":"切历史复验的回答 $tag"}}]}""")
        }
        val baseUrl = "http://127.0.0.1:${server.port}"

        var serviceId: String? = null
        var characterId: String? = null
        var hold: HearthStageHold? = null
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
            // 两段存好的历史：先写旧的一段、再写新的一段，所以打开人物时默认开的是新的一段。
            val older = log.append(character.id, null, olderQuestion, olderAnswer, "本机 · 测试", minutesAgo(2))
            val newest = log.append(character.id, null, newestQuestion, "切历史复验：新对话的回答 $tag", "本机 · 测试", minutesAgo(1))

            val gates = GatedHearthSessions(database)
            val model = HearthViewModel(context, gates::open)
            model.openChat(character)
            waitUntil(20_000) { !model.state.value.historyLoading && model.state.value.openConversation != null }
            assertEquals("默认应该打开最新的一段", newest, model.state.value.openConversation)

            // 切到旧的一段，并把这次读取停在半路。
            hold = gates.hold(HearthStage.Read(MESSAGES_READ))
            model.openConversation(older)
            assertEquals("切换开始时就该把目标定到新的一段", older, model.state.value.openConversation)
            assertTrue("读取期间页面必须处于历史未就绪状态", model.state.value.historyLoading)
            hold.await()

            // 就在这个窗口里发送：一次都不该出去，也不许被改派给上一段对话。
            model.send(refused)
            Thread.sleep(500)
            assertEquals("历史还没就绪时一次都不该发出请求", 0, calls.get())
            assertEquals("历史还没就绪时目标不该被改回上一段对话", older, model.state.value.openConversation)

            hold.letGo()
            hold = null
            waitUntil(20_000) { !model.state.value.historyLoading }
            assertTrue("旧对话的历史应该已经上屏", model.state.value.messages.any { it.text.contains(olderQuestion) })

            model.send(accepted)
            waitUntil(20_000) { calls.get() == 1 }
            waitUntil(20_000) { !model.state.value.busy }
            assertTrue(
                "历史就绪后的问题应该进刚打开的那一段",
                log.messages(older).any { it.text.contains(accepted) },
            )
            assertTrue(
                "历史就绪后的问题不该进默认打开的那一段",
                log.messages(newest).none { it.text.contains(accepted) },
            )
            assertTrue(
                "被拒的那条一次都不该入库",
                log.messages(older).none { it.text.contains(refused) } &&
                    log.messages(newest).none { it.text.contains(refused) },
            )
        } finally {
            hold?.letGo()
            characterId?.let { characters.delete(it) }
            serviceId?.let { settings.removeService(it) }
            restoreAudit(audit, auditBefore)
            server.close()
        }
    }

    /**
     * 第 2 条（返回再进入）：离开这一页再回来，重新进入时默认打开最新的一段对话，而它的历史要
     * 重新读。读取期间发送同样必须被拒绝——离开前打开的是旧的一段，放任发送就会把问题发给它。
     */
    @Test
    fun comingBackToThePageRefusesASendUntilTheStoredHistoryIsThere() {
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
        val olderQuestion = "返回再进入复验：旧对话里的问题 $tag"
        val newestQuestion = "返回再进入复验：默认打开的那段 $tag"
        val refused = "返回再进入复验：读取期间这条不该发出去 $tag"
        val accepted = "返回再进入复验：历史就绪后这条该进默认打开的那段 $tag"

        val calls = AtomicInteger(0)
        val server = LocalHttp {
            calls.incrementAndGet()
            HttpReply(200, """{"choices":[{"message":{"content":"返回再进入复验的回答 $tag"}}]}""")
        }
        val baseUrl = "http://127.0.0.1:${server.port}"

        var serviceId: String? = null
        var characterId: String? = null
        var hold: HearthStageHold? = null
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
            val older = log.append(character.id, null, olderQuestion, "返回再进入复验：旧对话的回答 $tag", "本机 · 测试", minutesAgo(2))
            val newest = log.append(character.id, null, newestQuestion, "返回再进入复验：新对话的回答 $tag", "本机 · 测试", minutesAgo(1))

            val gates = GatedHearthSessions(database)
            val model = HearthViewModel(context, gates::open)
            model.openChat(character)
            waitUntil(20_000) { !model.state.value.historyLoading && model.state.value.openConversation != null }
            // 离开之前停在旧的那一段：回来时页面默认要开最新的一段。
            model.openConversation(older)
            waitUntil(20_000) { !model.state.value.historyLoading }
            assertEquals(older, model.state.value.openConversation)

            model.leaveHearth()
            hold = gates.hold(HearthStage.Read(MESSAGES_READ))
            model.openChat(character)
            waitUntil(20_000) { model.state.value.historyLoading }
            hold.await()

            model.send(refused)
            Thread.sleep(500)
            assertEquals("刚回到页面、历史还没到时就该拒绝发送", 0, calls.get())

            hold.letGo()
            hold = null
            waitUntil(20_000) { !model.state.value.historyLoading }
            assertEquals("回到页面应该重新打开最新的一段", newest, model.state.value.openConversation)
            assertTrue("最新一段的历史应该已经上屏", model.state.value.messages.any { it.text.contains(newestQuestion) })

            model.send(accepted)
            waitUntil(20_000) { calls.get() == 1 }
            waitUntil(20_000) { !model.state.value.busy }
            assertTrue(
                "重新进入后的问题应该进默认打开的那一段",
                log.messages(newest).any { it.text.contains(accepted) },
            )
            assertTrue(
                "不该落进离开前打开的那一段",
                log.messages(older).none { it.text.contains(accepted) },
            )
            assertTrue(
                "被拒的那条一次都不该入库",
                log.messages(newest).none { it.text.contains(refused) } &&
                    log.messages(older).none { it.text.contains(refused) },
            )
        } finally {
            hold?.letGo()
            characterId?.let { characters.delete(it) }
            serviceId?.let { settings.removeService(it) }
            restoreAudit(audit, auditBefore)
            server.close()
        }
    }

    /**
     * 第 2 条（写库之后的异步刷新窗口）：一轮已经入库、`finishTurn()` / `replaceTurn()` 正在把
     * 会话列表与消息读回来刷新页面时，用户打开了另一段对话。这次刷新再晚也绝不能把页面拉回旧
     * 的一段——两条收尾路径都在最后读取之后复令牌。
     *
     * 时序同样用闸门摆：假服务先睡 1 秒，测试在这段里把那次「读消息」停住，再切对话。
     */
    @Test
    fun aLateRefreshAfterAStoredTurnNeverOverwritesTheConversationTheUserOpened() {
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
        val question = "刷新窗口复验：这一轮先进库 $tag"
        val otherAnswer = "刷新窗口复验：另一段的回答 $tag"
        val thirdAnswer = "刷新窗口复验：第三段的回答 $tag"

        val calls = AtomicInteger(0)
        val server = LocalHttp {
            calls.incrementAndGet()
            // 慢一点回来，测试才有时间在「写库之后、异步刷新期间」动手。
            Thread.sleep(1_000)
            HttpReply(200, """{"choices":[{"message":{"content":"刷新窗口复验的回答 $tag"}}]}""")
        }
        val baseUrl = "http://127.0.0.1:${server.port}"

        var serviceId: String? = null
        var characterId: String? = null
        var hold: HearthStageHold? = null
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

            val gates = GatedHearthSessions(database)
            val model = HearthViewModel(context, gates::open)
            model.openChat(character)
            waitUntil(20_000) { !model.state.value.historyLoading }

            // 第一轮：发出去，等它入库，并把写库之后那次「读消息」停住。
            hold = gates.hold(HearthStage.Read(MESSAGES_READ))
            model.send(question)
            waitUntil(20_000) { model.state.value.busy }
            hold.await()
            val stored = log.conversations(character.id).single()
            assertTrue("这一轮应该已经入库", log.messages(stored.id).any { it.text.contains(question) })

            val other = log.append(character.id, null, "刷新窗口复验：另一段 $tag", otherAnswer, "本机 · 测试", minutesAgo(5))
            model.openConversation(other)
            waitUntil(20_000) { model.state.value.openConversation == other && !model.state.value.historyLoading }
            hold.letGo()
            hold = null
            Thread.sleep(1_000)
            assertEquals("晚到的刷新不该把页面拉回上一段对话", other, model.state.value.openConversation)
            assertTrue("页面应该显示刚打开的那一段", model.state.value.messages.any { it.text.contains(otherAnswer) })
            assertFalse("换过页面之后不该还挂着生成中", model.state.value.busy)

            // 第二轮：重新生成走的是 replaceTurn，同一条「先写库、再异步刷新」的路径。
            val third = log.append(character.id, null, "刷新窗口复验：第三段 $tag", thirdAnswer, "本机 · 测试", minutesAgo(10))
            model.openConversation(stored.id)
            waitUntil(20_000) { model.state.value.openConversation == stored.id && !model.state.value.historyLoading }
            model.regenerate()
            waitUntil(20_000) { model.state.value.busy }
            hold = gates.hold(HearthStage.Read(MESSAGES_READ))
            hold.await()

            model.openConversation(third)
            waitUntil(20_000) { model.state.value.openConversation == third && !model.state.value.historyLoading }
            hold.letGo()
            hold = null
            Thread.sleep(1_000)
            assertEquals("replaceTurn 的晚到刷新也不该改动页面", third, model.state.value.openConversation)
            assertTrue("页面应该显示第三段", model.state.value.messages.any { it.text.contains(thirdAnswer) })
            assertTrue("重新生成还是应该落在原来那一段里", calls.get() >= 2)
        } finally {
            hold?.letGo()
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

/** 比现在早 [minutes] 分钟的库内时间戳：两段历史的先后要定死，默认打开哪一段才可预期。 */
private fun minutesAgo(minutes: Long): String =
    LocalDateTime.now().minusMinutes(minutes).truncatedTo(ChronoUnit.SECONDS).toString()

/** [ConversationLog.messages] 的 SQL 前缀：闸门用它认出「读这一段的历史」。 */
private const val MESSAGES_READ = "SELECT role, text, model FROM messages"

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
private val leftoverQuestions = listOf(
    "导航复验：这句会不会跟着页面走",
    "导航复验：这句还会发出去吗",
    "导航复验：离页之后这句还会发出去吗",
    "切历史复验：",
    "返回再进入复验：",
    "刷新窗口复验：",
)

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