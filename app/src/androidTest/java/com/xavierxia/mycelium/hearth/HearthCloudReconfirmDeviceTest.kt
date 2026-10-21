package com.xavierxia.mycelium.hearth

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xavierxia.mycelium.MainActivity
import com.xavierxia.mycelium.net.AuditEntry
import com.xavierxia.mycelium.net.AuditLog
import com.xavierxia.mycelium.net.HttpReply
import com.xavierxia.mycelium.net.LocalHttp
import com.xavierxia.mycelium.roundtable.CloudSettingsStore
import com.xavierxia.mycelium.roundtable.KeystoreSecretBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * P2-2 真机复验之二（手工走查没做成的两项之一）：在「我的 → 云端模型」里把已经确认过的服务换一个
 * 接口地址之后，回炉边发消息必须重新确认一次——弹「这个人物改用云端模型？」，取消就不发。
 *
 * 改地址走的是真实界面：点卡片上的「编辑」，用 Compose 的 `performTextReplacement` 直接替换
 * 「接口地址」输入框（不用 `adb shell input text`，不受输入法影响），再点「拉取模型」——源码里
 * `addService` 就在这一步落盘（`MyceliumApp.kt` 的 CloudSettingsScreen），拉取本身成功与否不影响
 * 地址已经改掉这件事。
 *
 * 假服务是进程内的 [LocalHttp]：它一次都不该被调用，所以只要它收到请求，这个测试就失败。
 */
@RunWith(AndroidJUnit4::class)
class HearthCloudReconfirmDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun changingTheSavedAddressMakesTheNextSendAskAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = CloudSettingsStore(context, KeystoreSecretBox("mycelium-cloud"))
        val database = HearthDatabase(context)
        val characters = CharacterStore { database.session() }
        val log = ConversationLog { database.session() }
        val audit = AuditLog(File(context.filesDir, "privacy/audit.json"))
        // 上一次跑挂掉（进程被杀，finally 没跑完）会留下同族数据，先清掉，测试才能反复跑。
        sweepEarlierRuns(characters, settings, audit)
        val auditBefore = audit.read()

        val tag = System.currentTimeMillis().toString().takeLast(6)
        val serviceName = "P2-2 复验服务 $tag"
        val characterName = "改址复验人物 $tag"
        val question = "改址之后这句还会发出去吗 $tag"
        val mustNotArrive = "不该出现的回答 $tag"

        val server = LocalHttp { HttpReply(200, """{"choices":[{"message":{"content":"$mustNotArrive"}}]}""") }
        val oldUrl = "http://127.0.0.1:${server.port}"
        // 同主机换端口：和原地址不同的接收方，端口 +1 上不跑任何服务。
        val newUrl = "http://127.0.0.1:${server.port + 1}"

        var serviceId: String? = null
        var characterId: String? = null
        try {
            val service = settings.addService(null, serviceName, oldUrl, "test-key-127")
            serviceId = service
            settings.saveModels(service, listOf("deepseek-test"))
            val oldTarget = cloudTarget(service, oldUrl)
            characterId = characters.create(
                CharacterDraft(
                    name = characterName,
                    personality = "真机复验用的临时人物，测试结束就删掉。",
                    greeting = "在的。",
                    modelKind = ModelKind.CLOUD,
                    serviceId = service,
                    serviceName = serviceName,
                    modelName = "deepseek-test",
                    cloudConfirmedTarget = oldTarget,
                ),
                now(),
            ).id

            // 1) 在「我的 → 云端模型」里把地址改掉（真实界面 + performTextReplacement）。
            compose.onAllNodesWithText("我的")[0].performClick()
            compose.waitUntil(20_000) { compose.exists("云端模型") }
            compose.onNodeWithText("云端模型").performClick()
            compose.waitUntil(20_000) { compose.exists("添加服务") }
            // 卡片可能排在屏幕外（用户自己的服务在前面），performScrollToNode 会滚过去找。
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(serviceName))
            compose.clickButtonBelow(serviceName, "编辑")
            compose.waitUntil(20_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 3 }
            compose.onAllNodes(hasSetTextAction() and hasText(oldUrl))[0].performTextReplacement(newUrl)
            compose.onNodeWithText("拉取模型").performClick()

            var storedUrl: String? = null
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                storedUrl = settings.services().firstOrNull { it.id == service }?.baseUrl
                if (storedUrl == newUrl) break
                Thread.sleep(200)
            }
            assertEquals("界面上的「拉取模型」没有把新地址落盘", newUrl, storedUrl)

            // 2) 回炉边，点开这个已经确认过的人物，发一句话。人物列表是进页面后才异步读库的。
            compose.onAllNodesWithText("炉边")[0].performClick()
            compose.waitUntil(20_000) { compose.exists("新建人物") }
            compose.waitUntil(20_000) { compose.exists(characterName) }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(characterName))
            compose.onNodeWithText(characterName).performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).performTextInput(question)
            compose.onNodeWithText("发送").performClick()

            // 3) 地址变了，所以先确认：弹框出现，一个请求都没出去。
            compose.waitUntil(20_000) { compose.exists("这个人物改用云端模型？") }
            compose.onNodeWithText("这个人物改用云端模型？").assertIsDisplayed()
            assertEquals("弹确认框的时候不该有请求出去", 0, server.count.get())
            assertTrue("假服务不该收到任何调用，实际 ${server.calls()}", server.calls().isEmpty())

            // 4) 取消：这一轮不发，问题回到输入框。
            compose.onNodeWithText("取消").performClick()
            compose.waitUntil(20_000) { compose.exists("已取消，这一轮没有发出去") }
            compose.onNodeWithText("已取消，这一轮没有发出去").assertIsDisplayed()
            assertTrue(
                "取消之后弹框应该关掉",
                compose.onAllNodesWithText("这个人物改用云端模型？").fetchSemanticsNodes().isEmpty(),
            )
            compose.onNode(hasSetTextAction()).assert(hasText(question))
            assertEquals("取消之后也不该有请求出去", 0, server.count.get())
            assertTrue("假服务始终不该收到调用，实际 ${server.calls()}", server.calls().isEmpty())

            // 取消没有偷偷把这次改动写成「已确认」，也没有留下这一轮。
            assertEquals(oldTarget, characters.character(requireNotNull(characterId))?.cloudConfirmedTarget)
            assertTrue(
                "取消的这一轮不该留下任何对话",
                log.conversations(requireNotNull(characterId)).isEmpty(),
            )
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

/**
 * 点同一张卡片里的按钮。人物卡和云服务卡都各带一个「编辑」，只按文字找会同时命中好几张卡，
 * 所以用锚点（测试数据的名字，唯一）的位置把按钮定到它下面那张卡上。
 */
private fun ComposeContentTestRule.clickButtonBelow(anchorText: String, buttonText: String) {
    val anchor = onNodeWithText(anchorText).fetchSemanticsNode().boundsInRoot
    val buttons = onAllNodesWithText(buttonText, useUnmergedTree = true)
    val nodes = buttons.fetchSemanticsNodes()
    // 同一张卡片里按钮和标题的顶边不一定齐平（字体行高不同、Row 对齐方式不同），所以按**垂直距离**
    // 取最近的那个，而不是「必须在标题下方」。锚点名字带唯一标签，最近的按钮就是这张卡上的。
    val anchorMiddle = (anchor.top + anchor.bottom) / 2f
    val index = nodes.indices.minByOrNull { candidate ->
        val middle = (nodes[candidate].boundsInRoot.top + nodes[candidate].boundsInRoot.bottom) / 2f
        kotlin.math.abs(middle - anchorMiddle)
    }
    checkNotNull(index) { "「$anchorText」旁边找不到「$buttonText」，界面结构变了？" }
    buttons[index].performTouchInput { click() }
}

/** 人物和对话的时间戳用应用自己的格式，排序规则才一致。 */
private fun now(): String = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

/** 把隐私记录恢复到测试开始时的内容：只该清掉测试自己写进去的那几条。 */
private fun restoreAudit(audit: AuditLog, before: List<AuditEntry>) {
    audit.clear()
    before.forEach { audit.record(it) }
}

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
