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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
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
 *
 * 每一个等待都是有界的，超时会把全部线程栈打进 instrument 输出；闸门一律在 `finally` 里放行
 * （[GatedHearthSessions.releaseAll]），「什么都没发生」用有界观察窗口（[expectNothingFor]）表达，
 * 不用「睡一会儿」去撞窗口；整个用例另有一道 [BoundedDeviceTest] 硬上限。
 */
@RunWith(AndroidJUnit4::class)
class HearthNavigationDuringTurnDeviceTest {

    /** 单个用例的硬上限：到点连线程栈一起报出来，再判失败，绝不再出现「没人管地挂住」。 */
    @get:Rule
    val bounded: TestRule = BoundedDeviceTest(240_000)

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
            awaitOpenCharacter(model, character)

            // 第一轮：生成中「新对话」。
            sendOrFail(model, question)
            waitUntil(20_000, "第一轮开始生成（busy）", state(model)) { model.state.value.busy }
            assertEquals("第一次请求应该已经到达假服务", 1, calls.get())
            model.newConversation()
            waitUntil(5_000, "换会话之后不再生成中") { !model.state.value.busy }
            assertFalse("换会话之后不该还显示生成中", model.state.value.busy)
            assertNull("换会话之后不该还挂着流式内容", model.state.value.streaming)
            assertNull("换会话之后不该留着等待确认的问题", model.state.value.sendConfirm)

            // 旧请求晚到的回答不能上屏、不能入库。
            waitUntil(20_000, "旧的一轮已经收尾") { !model.state.value.busy }
            assertFalse(
                "晚到的回答上屏了",
                model.state.value.messages.any { it.text.contains(lateAnswer) },
            )
            assertTrue("晚到的回答入库了", log.conversations(character.id).isEmpty())

            // 页面确实能继续用：再发一次，请求必须真的发出去。
            sendOrFail(model, "$question 第二轮")
            waitUntil(20_000, "第二轮请求到达假服务", state(model)) { calls.get() >= 2 }
            assertEquals("页面应该还能继续发送", 2, calls.get())
            waitUntil(20_000, "第二轮开始生成", state(model)) { model.state.value.busy }

            // 第二轮：生成中切到测试模式。同样必须停下并清干净。
            model.setMode(HearthMode.TEST)
            waitUntil(5_000, "切到测试模式后不再生成中") {
                !model.state.value.busy && model.state.value.mode == HearthMode.TEST
            }
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
        var gates: GatedHearthSessions? = null
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
            val gatekeeper = GatedHearthSessions(database)
            gates = gatekeeper
            val model = HearthViewModel(context, gatekeeper::open)

            model.openChat(character)
            awaitOpenCharacter(model, character)
            // 再等 ViewModel 启动时那次读取（seeder、服务列表）收尾：它自己那次写事务不能被下面这道
            // 闸门抢走，否则被停住的不是确认写，断言就变成假阴性。
            waitUntil(20_000, "ViewModel 启动读取完成", state(model)) { model.state.value.services.any { it.id == serviceId } }
            // 进测试模式：这一轮不留记录，库里之后出现的任何变化都只可能来自确认任务本身。
            model.setMode(HearthMode.TEST)
            waitUntil(20_000, "切到测试模式") { model.state.value.mode == HearthMode.TEST }
            model.send(question)
            waitUntil(20_000, "弹出云端确认框") { model.state.value.sendConfirm != null }
            assertEquals("弹确认框的时候不该有请求", 0, calls.get())

            // 场景一：确认任务卡在数据库写事务上时取消弹窗，并在同一时刻改一次人物名字。
            hold = gatekeeper.hold(HearthStage.Transaction(2))
            model.confirmSend()
            hold.await()
            model.dismissSendConfirm()
            val editedName = "导航复验人物（改过名字）$tag"
            characters.update(character.id, draftOf(character).copy(name = editedName), now())
            hold.letGo()
            // 用「这次写事务真的走完」当同步点，而不是睡一会儿：它回来就代表写库的判定做完了。
            hold.awaitCallFinished(20_000)
            hold = null
            expectNothingFor(2_000, "取消弹窗之后这个确认任务不该发出请求") { calls.get() != 0 }

            assertEquals("取消弹窗之后这个确认任务不该发出请求", 0, calls.get())
            val afterDismiss = requireNotNull(characters.character(character.id))
            assertEquals("人物上的确认状态不该被这个任务改写", staleTarget, afterDismiss.cloudConfirmedTarget)
            assertEquals("停住期间编辑的名字不该被旧快照覆盖回去", editedName, afterDismiss.name)
            assertNull("取消之后不该还挂着这个确认框", model.state.value.sendConfirm)
            assertTrue("不该建出对话", log.conversations(character.id).isEmpty())

            // 场景二：再来一次，这次在同一个窗口里直接离开页面。
            model.send(secondQuestion)
            waitUntil(20_000, "第二次弹出云端确认框") { model.state.value.sendConfirm != null }
            hold = gatekeeper.hold(HearthStage.Transaction(2))
            model.confirmSend()
            hold.await()
            model.leaveHearth()
            hold.letGo()
            hold.awaitCallFinished(20_000)
            hold = null
            expectNothingFor(2_000, "离开页面之后这个确认任务不该发出请求") { calls.get() != 0 }

            assertEquals("离开页面之后这个确认任务不该发出请求", 0, calls.get())
            val afterLeave = requireNotNull(characters.character(character.id))
            assertEquals("确认状态仍然不该被改写", staleTarget, afterLeave.cloudConfirmedTarget)
            assertEquals("改过的名字仍然应该保留", editedName, afterLeave.name)
            assertTrue("两轮都不该建出对话", log.conversations(character.id).isEmpty())
            assertTrue("提问框的内容应该跟着页面一起收走", model.state.value.pendingQuestion == null)
        } finally {
            hold?.letGo()
            gates?.releaseAll()
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
            awaitOpenCharacter(model, character)
            assertEquals("默认应该打开最新的一段", newest, model.state.value.openConversation)

            // 切到旧的一段，并把这次读取停在半路。
            hold = gates.hold(HearthStage.Read(MESSAGES_READ))
            model.openConversation(older)
            assertEquals("切换开始时就该把目标定到新的一段", older, model.state.value.openConversation)
            assertTrue("读取期间页面必须处于历史未就绪状态", model.state.value.historyLoading)
            hold.await()

            // 就在这个窗口里发送：一次都不该出去，也不许被改派给上一段对话。
            model.send(refused)
            expectNothingFor(1_000, "历史还没就绪时一次都不该发出请求，目标也不该被改回上一段对话") {
                calls.get() != 0 || model.state.value.openConversation != older
            }

            hold.letGo()
            hold = null
            waitUntil(20_000, "放行之后历史读完") { !model.state.value.historyLoading }
            assertTrue("旧对话的历史应该已经上屏", model.state.value.messages.any { it.text.contains(olderQuestion) })

            model.send(accepted)
            waitUntil(20_000, "历史就绪后的这一句到达假服务") { calls.get() == 1 }
            waitUntil(20_000, "这一轮收尾") { !model.state.value.busy }
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
            awaitOpenCharacter(model, character)
            // 离开之前停在旧的那一段：回来时页面默认要开最新的一段。
            model.openConversation(older)
            waitUntil(20_000, "切到旧的那一段", state(model)) { !model.state.value.historyLoading }
            assertEquals(older, model.state.value.openConversation)

            model.leaveHearth()
            hold = gates.hold(HearthStage.Read(MESSAGES_READ))
            model.openChat(character)
            waitUntil(20_000) { model.state.value.historyLoading }
            hold.await()

            model.send(refused)
            expectNothingFor(1_000, "刚回到页面、历史还没到时就该拒绝发送") { calls.get() != 0 }

            hold.letGo()
            hold = null
            waitUntil(20_000, "放行之后历史读完") { !model.state.value.historyLoading }
            assertEquals("回到页面应该重新打开最新的一段", newest, model.state.value.openConversation)
            assertTrue("最新一段的历史应该已经上屏", model.state.value.messages.any { it.text.contains(newestQuestion) })

            model.send(accepted)
            waitUntil(20_000, "回到页面后的这一句到达假服务") { calls.get() == 1 }
            waitUntil(20_000, "这一轮收尾") { !model.state.value.busy }
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
     * 时序同样用闸门摆：假服务先睡 1 秒，测试在「这一轮已经开始生成」之后把闸门摆上，停住的是
     * 写库之后那次「读消息」，再切对话。闸门摆早了停的是 window 读，那时 busy 还没置上。
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
        val firstAnswer = "刷新窗口复验的回答 $tag（第 1 次）"

        val calls = AtomicInteger(0)
        val server = LocalHttp {
            val nth = calls.incrementAndGet()
            // 慢一点回来，测试才有时间在「写库之后、异步刷新期间」动手；每次正文带序号，重做那一轮
            // 如果真的写回去了，库里就不再是第 1 次的回答。
            Thread.sleep(1_000)
            HttpReply(200, """{"choices":[{"message":{"content":"刷新窗口复验的回答 $tag（第 $nth 次）"}}]}""")
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
            awaitOpenCharacter(model, character)

            // 第一轮：先发出去、等这一轮真的开始生成（那次 window 读已经过去），再把「写库之后那次
            // 读消息」停住。闸门摆早了停的是 window 读，那时 busy 还没置上，等 busy 就永远等不到。
            sendOrFail(model, question)
            waitUntil(20_000, "第一轮开始生成（busy）", state(model)) { model.state.value.busy }
            hold = gates.hold(HearthStage.Read(MESSAGES_READ))
            hold.await()
            val stored = log.conversations(character.id).single()
            assertTrue("这一轮应该已经入库", log.messages(stored.id).any { it.text.contains(question) })

            val other = log.append(character.id, null, "刷新窗口复验：另一段 $tag", otherAnswer, "本机 · 测试", minutesAgo(5))
            model.openConversation(other)
            waitUntil(20_000, "切到另一段对话") { model.state.value.openConversation == other && !model.state.value.historyLoading }
            hold.letGo()
            hold.awaitCallFinished(20_000)
            hold = null
            expectNothingFor(1_500, "晚到的刷新不该把页面拉回上一段对话") { model.state.value.openConversation != other }
            assertTrue("页面应该显示刚打开的那一段", model.state.value.messages.any { it.text.contains(otherAnswer) })
            assertFalse("换过页面之后不该还挂着生成中", model.state.value.busy)

            // 第二轮：重新生成走的是 replaceTurn。这次把它的**写事务**停住，再在写事务等待期间导航：
            // 数据库于是拒写，收尾只能走 NonCancellable 那条路（协程取消挡不住它），也就只有令牌能
            // 拦住这一轮把旧回答盖到新页面上。停在读上是停不住这条路径的。
            val third = log.append(character.id, null, "刷新窗口复验：第三段 $tag", thirdAnswer, "本机 · 测试", minutesAgo(10))
            model.openConversation(stored.id)
            waitUntil(20_000, "切回这一段") { model.state.value.openConversation == stored.id && !model.state.value.historyLoading }
            hold = gates.hold(HearthStage.Transaction(2))
            model.regenerate()
            hold.await()

            model.openConversation(third)
            waitUntil(20_000, "切到第三段") { model.state.value.openConversation == third && !model.state.value.historyLoading }
            hold.letGo()
            hold.awaitCallFinished(20_000)
            hold = null
            expectNothingFor(1_500, "replaceTurn 的晚到刷新不该改动页面") { model.state.value.openConversation != third }
            assertEquals("replaceTurn 的晚到刷新也不该改动页面", third, model.state.value.openConversation)
            assertTrue("页面应该显示第三段", model.state.value.messages.any { it.text.contains(thirdAnswer) })
            assertEquals(
                "被写事务拒写的这一轮不许改写库里那一段",
                firstAnswer,
                log.messages(stored.id).last().text,
            )
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

/** 等一个条件成立；等不到就让断言失败（带页面状态与全部线程栈），而不是让测试挂住。 */
private fun waitUntil(
    timeoutMs: Long,
    what: String = "条件",
    state: () -> String = { "" },
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(50)
    }
    assertTrue(
        "等待超时（${timeoutMs}ms）：$what 一直没成立。${state()}\n全部线程栈：\n${threadDump()}",
        condition(),
    )
}

/** 失败信息里带上页面状态：断言为什么没成立，一眼能看出来。 */
private fun state(model: HearthViewModel): () -> String = { "\n页面状态=${model.state.value}" }

/**
 * 这一轮必须真的被页面接住。页面拒绝发送（这一段的历史没读出来）、还没打开人物、或把它停在
 * 云端确认框上，都会让下面的 `busy` 永远等不到，所以这几个前提先单独断言掉，失败信息直接指向原因。
 */
private fun sendOrFail(model: HearthViewModel, text: String) {
    model.send(text)
    val current = model.state.value
    assertNotNull("发送没有落点：页面还没打开人物（发送被静默丢弃）", current.openCharacter)
    assertFalse("发送被拒：这一段的历史没读出来（页面提示：${current.notice}）", current.historyFailed)
    assertNull("发送停在云端确认框上，没有真的开始（待确认：${current.sendConfirm}）", current.sendConfirm)
}

/**
 * 等到页面真的打开了这个人物、并且这一段的历史读完。
 *
 * 不能只等 `!historyLoading`：ViewModel 刚建出来时 `historyLoading` 的默认值就是 false，
 * 只等它会在 `openChat` 的协程还没跑起来时就直接通过，接着的 `send` 会因为「还没有打开的人物」
 * 被静默丢掉——这正是 `aLateRefresh…` 这一例原来真机上必挂的原因。
 */
private fun awaitOpenCharacter(model: HearthViewModel, character: HearthCharacter) {
    waitUntil(20_000, "打开人物、这一段的历史读完", state(model)) {
        model.state.value.openCharacter?.id == character.id && !model.state.value.historyLoading
    }
    assertFalse("打开人物时历史读取失败了：${model.state.value.notice}", model.state.value.historyFailed)
}

/**
 * 在一段**有界**的观察窗口里一直盯着「不该发生的事」：一发生立刻失败，窗口走完才放行。
 *
 * 「什么都没发生」这种断言本来就需要看一段时间，这里不把它当同步用（不用「睡一会儿再断言」
 * 去撞窗口），而是把窗口本身变成断言：窗口里任何一刻违反了 [forbidden]，失败信息指到那一刻。
 */
private fun expectNothingFor(millis: Long, what: String, forbidden: () -> Boolean) {
    val deadline = System.currentTimeMillis() + millis
    while (true) {
        assertFalse("$what（${millis}ms 观察窗口里出现了）", forbidden())
        if (System.currentTimeMillis() >= deadline) return
        Thread.sleep(25)
    }
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