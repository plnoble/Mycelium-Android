package com.xavierxia.mycelium.hearth

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xavierxia.mycelium.MainActivity
import com.xavierxia.mycelium.net.AuditEntry
import com.xavierxia.mycelium.net.AuditLog
import com.xavierxia.mycelium.net.GatewayPurpose
import com.xavierxia.mycelium.net.HttpReply
import com.xavierxia.mycelium.net.LocalHttp
import com.xavierxia.mycelium.roundtable.CloudSettingsStore
import com.xavierxia.mycelium.roundtable.KeystoreSecretBox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P2-2 真机复验之一（手工走查没做成的两项之一）：云端慢回答下点「停止」之后，晚到的回答既不上屏、
 * 也不入库，而这次请求在隐私记录里留了痕。
 *
 * 假服务是进程内的 [LocalHttp]，不连电脑、不走 adb reverse：它先睡 5 秒，再回一个 OpenAI 兼容的
 * JSON。停止时应用会主动断开这条连接（`CloudChatModel.stop()` → `GatewayAbort`），所以那 5 秒后
 * 写回的是已经关掉的 socket。界面用真实的 [MainActivity]，输入用 Compose 的 `performTextInput`，
 * 完全不碰输入法。
 */
@RunWith(AndroidJUnit4::class)
class HearthCloudStopDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

    /**
     * 假服务在被停止的那条连接上写回答时 socket 已经关了，写操作会在假服务自己的线程里抛
     * IOException；Android 上任意线程的未捕获异常都会杀掉整个进程（也就杀掉这次 instrument）。
     * 这里只咽掉「假服务线程抛出的 IOException」，别的异常照旧交给原来的处理器，不掩盖应用自己的崩溃。
     */
    @Before
    fun keepTheFakeServerThreadFromKillingTheProcess() {
        val mainThread = Looper.getMainLooper().thread
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val fromTheFakeServer = error is IOException &&
                thread !== mainThread &&
                error.stackTrace.any { it.className == "com.xavierxia.mycelium.net.LocalHttp" }
            if (!fromTheFakeServer) previousHandler?.uncaughtException(thread, error)
        }
    }

    @After
    fun restoreTheDefaultHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test
    fun aStoppedCloudTurnNeverShowsOrStoresTheLateAnswer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = CloudSettingsStore(context, KeystoreSecretBox("mycelium-cloud"))
        val database = HearthDatabase(context)
        val characters = CharacterStore { database.session() }
        val log = ConversationLog { database.session() }
        val audit = AuditLog(File(context.filesDir, "privacy/audit.json"))
        // 上一次跑挂掉（进程被杀，finally 没跑完）会留下同族数据，先清掉，测试才能反复跑。
        sweepEarlierRuns(characters, settings, audit)
        val auditBefore = audit.read()

        // 名字带时间戳：上一次跑剩下的数据（如果有）不会和这次重名，测试可以反复跑。
        val tag = System.currentTimeMillis().toString().takeLast(6)
        val serviceName = "P2-2 停止复验 $tag"
        val characterName = "停止复验人物 $tag"
        val lateAnswer = "晚到的回答 $tag"
        val question = "停止测试：这句还会再出现吗 $tag"

        val received = CountDownLatch(1)
        val answered = CountDownLatch(1)
        val wasReceived = AtomicBoolean(false)
        val server = LocalHttp {
            wasReceived.set(true)
            received.countDown()
            Thread.sleep(5_000)
            answered.countDown()
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
                    // 已经确认过这个服务在这个地址上收到内容：这次不该再弹确认框。
                    cloudConfirmedTarget = cloudTarget(service, baseUrl),
                ),
                now(),
            ).id

            // 进「炉边」，点开这个云端人物。人物列表是进页面后才异步读库的，先等它读出来。
            compose.onAllNodesWithText("炉边")[0].performClick()
            compose.waitUntil(20_000) { compose.exists("新建人物") }
            compose.waitUntil(20_000) { compose.exists(characterName) }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(characterName))
            compose.onNodeWithText(characterName).performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("云端").assertIsDisplayed()

            compose.onNode(hasSetTextAction()).performTextInput(question)
            compose.onNodeWithText("发送").performClick()

            // 请求真的到了假服务手里，然后才是那 5 秒的慢回答。
            assertTrue("假服务没有收到这次请求", received.await(15, TimeUnit.SECONDS))
            compose.waitUntil(15_000) { compose.exists("停止") }
            compose.onNodeWithText("停止").performClick()

            // 等假服务把「晚到的回答」写回那条已经被应用断开的连接。
            assertTrue("假服务没有走完它的 5 秒", answered.await(15, TimeUnit.SECONDS))
            Thread.sleep(500)
            compose.waitUntil(15_000) { !compose.exists("停止") }
            compose.waitForIdle()

            assertTrue("假服务确实收到过请求", wasReceived.get())
            assertTrue(
                "界面上出现了晚到的回答",
                compose.onAllNodesWithText(lateAnswer).fetchSemanticsNodes().isEmpty(),
            )

            // 库里不能有这一轮：没有含这条回答的消息，连这一段对话都不该建。
            val conversations = log.conversations(requireNotNull(characterId))
            assertTrue("停止后不该留下这一段对话，实际 ${conversations.size} 段", conversations.isEmpty())
            val stored = conversations.flatMap { log.messages(it.id) }
            assertTrue("库里不该有这条晚到的回答", stored.none { it.text.contains(lateAnswer) })
            assertTrue("库里不该留下这一轮的任何消息，实际 ${stored.size} 条", stored.isEmpty())

            // 但这次请求必须留下隐私记录：停止只是断开了它，不是没发生。
            val after = audit.read()
            assertEquals("这次请求应该刚好留下一条审计记录", auditBefore.size + 1, after.size)
            val entry = after.drop(auditBefore.size).single()
            assertEquals(GatewayPurpose.HearthChat, entry.purpose)
            assertFalse("被停止的请求不该记成成功", entry.succeeded)
            assertNull("被断开时拿不到状态码", entry.status)
            assertTrue("沉浸模式要留下这轮问的话", entry.sentBody.contains(question))
        } finally {
            characterId?.let { characters.delete(it) }
            serviceId?.let { settings.removeService(it) }
            restoreAudit(audit, auditBefore)
            server.close()
        }
    }
}

/** 界面上还有没有这段文字（找得到就是有，找不到不是失败）。 */
private fun ComposeContentTestRule.exists(text: String): Boolean =
    onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

/** 这一族真机测试自己用的名字前缀和问句，只用来认自己留下的东西。 */
private val leftoverNames = listOf("P2-2 停止复验 ", "P2-2 复验服务 ", "停止复验人物 ", "改址复验人物 ")
private val leftoverQuestions = listOf("停止测试：这句还会再出现吗", "改址之后这句还会发出去吗")

/**
 * 清掉上一次跑挂掉时留下的数据。测试进程被杀时 `finally` 不会执行，人物、服务和那条审计记录
 * 就会留在真机上；这里只认本测试自己用的名字前缀和问句，别人的人物的服务一概不碰。
 */
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

/** 人物和对话的时间戳用应用自己的格式，排序规则才一致。 */
private fun now(): String = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

/** 把隐私记录恢复到测试开始时的内容：只该清掉测试自己写进去的那条。 */
private fun restoreAudit(audit: AuditLog, before: List<AuditEntry>) {
    audit.clear()
    before.forEach { audit.record(it) }
}
