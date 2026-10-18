package com.xavierxia.mycelium.hearth

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xavierxia.mycelium.model.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P2-2 审稿第 8 条的真机验证：跟不跟随要看真实视口是不是停在内容底部，以及用户自己的滚动意图。
 * 四种情况都在这里跑：长回答内部上翻、跨消息上翻、主动回到底部、底部时的自动滚动。
 *
 * 只渲染 [ChatTimeline]（对话页里真正管滚动的那一段），状态由测试直接喂，不碰 ViewModel。
 * 手势都放慢（几秒走几百像素），免得惯性滚动把落点甩到别处。
 */
@RunWith(AndroidJUnit4::class)
class HearthTimelineFollowDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private val listState = LazyListState()
    private var messages: List<HearthMessage> by mutableStateOf(emptyList())
    private var streaming: String? by mutableStateOf(null)
    private var conversationId: String? by mutableStateOf("a")

    private val character = HearthCharacter(
        id = "c1",
        name = "老周",
        avatarChar = "周",
        personality = "话不多",
        speech = "简短",
        background = "",
        relationship = "",
        greeting = "你好",
        modelKind = ModelKind.LOCAL,
        modelId = "m1",
        modelName = "测试模型",
        serviceId = "",
        serviceName = "",
        cloudConfirmedTarget = "",
        temperature = 0.7f,
        maxTokens = 256,
        color = 0,
        updatedAt = "2026-10-18T00:00:00",
    )

    private fun setContent() {
        rule.setContent {
            ChatTimeline(
                messages = messages,
                streaming = streaming,
                streamLabel = null,
                greeting = character.greeting,
                character = character,
                conversationId = conversationId,
                modifier = Modifier.fillMaxSize(),
                listState = listState,
            )
        }
        settle()
    }

    /** 等自动滚动和惯性滚动都停下来。 */
    private fun settle() {
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { !listState.isScrollInProgress }
        rule.waitForIdle()
    }

    private fun userMessage(text: String) = HearthMessage(ChatRole.USER, text, null)

    private fun assistantMessage(text: String) = HearthMessage(ChatRole.ASSISTANT, text, "本机 · 测试模型")

    /** 一条比屏幕长很多的消息：最后一项会超出一屏，正是审稿说的那种回答。 */
    private fun longAnswer(lines: Int, prefix: String = "答"): String =
        (1..lines).joinToString("\n") { "$prefix 第 $it 句，写得长一点，一行放不下的内容。" }

    /** 短一点的消息，但要够多，列表才真的能滚起来。 */
    private fun shortPair(turn: Int): List<HearthMessage> = listOf(
        userMessage("第 $turn 问：今天怎么样？"),
        assistantMessage("第 $turn 答：还行，随便写两句占点高度。"),
    )

    private fun longHistory(turns: Int): List<HearthMessage> = (1..turns).flatMap { shortPair(it) }

    private fun touch() = rule.onNode(hasScrollToIndexAction())

    /** 用户用手指回看（内容跟着手指往下走）。 */
    private fun userScrollsBack(pixels: Float, durationMillis: Long) {
        touch().performTouchInput {
            swipeDown(startY = center.y, endY = center.y + pixels, durationMillis = durationMillis)
        }
        settle()
    }

    /** 用户自己往下翻（内容跟着手指往上走）。 */
    private fun userScrollsForward(pixels: Float, durationMillis: Long) {
        touch().performTouchInput {
            swipeUp(startY = center.y, endY = center.y - pixels, durationMillis = durationMillis)
        }
        settle()
    }

    private fun atBottom(): Boolean = listState.isAtContentBottom()

    /** 不带容差：内容底边真的在视口里，一行都没被挡在下面。 */
    private fun tailVisible(): Boolean {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return true
        return last.index == info.totalItemsCount - 1 &&
            last.offset + last.size <= info.viewportEndOffset - info.afterContentPadding + 1
    }

    /** 视口第一项的实测位置：用 layoutInfo，不依赖 ScrollPosition 里可能滞后的记录。 */
    private fun measuredFirst(): Pair<Int, Int>? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull()?.let { it.index to it.offset }

    /** 失败时能直接看出视口数字，不用靠猜。 */
    private fun viewport(): String {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        return "first=${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset}" +
            " visibleCount=${info.visibleItemsInfo.size}" +
            " total=${info.totalItemsCount}" +
            " lastIndex=${last?.index} lastOffset=${last?.offset} lastSize=${last?.size}" +
            " viewportEnd=${info.viewportEndOffset} afterPadding=${info.afterContentPadding}" +
            " canScrollForward=${listState.canScrollForward}" +
            " atBottom=${atBottom()} tailVisible=${tailVisible()}"
    }

    @Test
    fun longAnswerScrolledBackInsideKeepsTheViewportStillWhileTokensArrive() {
        messages = listOf(userMessage("讲讲你的一天"))
        streaming = longAnswer(60)
        setContent()

        // 摆好「用户跟着这条长回答到了末尾」的样子（长回答比一屏长，所以能停在它中间）。
        rule.runOnIdle { listState.requestScrollToItem(1, 100_000) }
        settle()
        assertTrue("应该先停在长回答的末尾：${viewport()}", atBottom())
        assertTrue("应该已经在长回答里面：${viewport()}", listState.firstVisibleItemScrollOffset > 0)

        // 用户慢慢往回翻一大段，停在长回答中间（上一条消息还没露出来）。
        userScrollsBack(pixels = 700f, durationMillis = 5_000)
        val indexBefore = listState.firstVisibleItemIndex
        val offsetBefore = listState.firstVisibleItemScrollOffset
        assertEquals("用户应该还在长回答这一项里：${viewport()}", 1, indexBefore)
        assertTrue("用户应该停在长回答中间，不是它的开头：${viewport()}", offsetBefore > 0)
        assertFalse("用户上翻以后不该还算在底部：${viewport()}", atBottom())

        // 流式继续输出：一个字都不许抢滚动。
        streaming = longAnswer(61)
        settle()
        assertEquals("流式输出把用户拉回了长回答开头：${viewport()}", indexBefore, listState.firstVisibleItemIndex)
        assertEquals("流式输出动了用户的滚动位置：${viewport()}", offsetBefore, listState.firstVisibleItemScrollOffset)

        // 用户再往后看一点：同样不能被流式输出改动位置。
        userScrollsForward(pixels = 200f, durationMillis = 4_000)
        val offsetInto = listState.firstVisibleItemScrollOffset
        assertTrue("用户应该翻到了长回答更后面：${viewport()}", offsetInto > offsetBefore)
        assertFalse("还没到底：${viewport()}", atBottom())
        streaming = longAnswer(62)
        settle()
        assertEquals("长回答内部的位置被流式输出改了：${viewport()}", offsetInto, listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun scrollingBackAcrossMessagesKeepsTheViewportStillWhileTokensArrive() {
        messages = longHistory(30)
        streaming = "正在写的一小段"
        setContent()
        val bottomIndex = listState.firstVisibleItemIndex
        assertTrue("一开始应该跟到底部：${viewport()}", atBottom())

        // 回到前面的消息。
        userScrollsBack(pixels = 1_500f, durationMillis = 6_000)
        val indexBefore = listState.firstVisibleItemIndex
        val offsetBefore = listState.firstVisibleItemScrollOffset
        assertTrue("应该已经翻回较早的消息了：${viewport()}", indexBefore < bottomIndex - 2)
        assertFalse("翻回去以后不该还算在底部：${viewport()}", atBottom())

        // 新的一轮回答到达：不许把用户拉回底部。
        messages = messages + assistantMessage("新到的一条回答")
        streaming = "新到的一条回答正在变长"
        settle()
        assertEquals("跨消息上翻后位置被改了：${viewport()}", indexBefore, listState.firstVisibleItemIndex)
        assertEquals("跨消息上翻后偏移被改了：${viewport()}", offsetBefore, listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun returningToTheBottomByHandResumesFollowing() {
        messages = longHistory(30)
        setContent()
        assertTrue("一开始应该跟到底部：${viewport()}", atBottom())

        // 先上翻离开底部。
        var guard = 0
        while (atBottom() && guard++ < 8) {
            userScrollsBack(pixels = 1_500f, durationMillis = 6_000)
        }
        assertFalse("用户应该已经离开底部：${viewport()}", atBottom())

        // 再自己翻回底部：一路往下翻到真的到底为止。
        guard = 0
        while (!atBottom() && guard++ < 12) {
            userScrollsForward(pixels = 2_000f, durationMillis = 6_000)
        }
        assertTrue("用户手翻到底以后应该算在底部：${viewport()}", atBottom())

        // 新内容到达：应该重新跟着走。
        messages = messages + assistantMessage("刚刚到的新回答")
        settle()
        assertTrue("用户回到底部后没有恢复自动跟随：${viewport()}", tailVisible())
        rule.onNodeWithText("刚刚到的新回答").assertIsDisplayed()
    }

    @Test
    fun openingAnotherConversationFollowsItsNewestMessageAgain() {
        conversationId = "a"
        messages = longHistory(30)
        setContent()
        assertTrue("一开始应该跟到底部：${viewport()}", atBottom())

        // 在 A 里上翻，然后打开另一段对话：跟随状态不该跟着带过去。
        userScrollsBack(pixels = 1_500f, durationMillis = 6_000)
        assertFalse("用户应该已经离开底部：${viewport()}", atBottom())

        conversationId = "b"
        messages = longHistory(20) + assistantMessage("B 里最后一条")
        settle()
        assertTrue("换对话以后应该看到新那段的最新一条：${viewport()}", tailVisible())
        rule.onNodeWithText("B 里最后一条").assertIsDisplayed()
    }

    /**
     * P2-2 复审 r2 第 6 条第一个窗口：换到 B 以后，手势监听必须改 B 的状态。改前它只以 listState
     * 为键、一直抓着 A 的那份状态，所以在 B 里上翻会被记到 A 上，B 的 followTail 保持 true，
     * 下一次追加内容又把视口拉回底部。
     */
    @Test
    fun scrollingBackInsideTheSecondConversationKeepsTheViewportStill() {
        conversationId = "a"
        messages = longHistory(30)
        setContent()
        assertTrue("一开始应该跟到底部：${viewport()}", atBottom())

        // 在 A 里上翻：这时候手势改的是 A 的状态。
        userScrollsBack(pixels = 1_500f, durationMillis = 6_000)
        assertFalse("用户应该已经离开底部：${viewport()}", atBottom())

        // 换到 B：状态按新的一段重建，先跟到底部。
        conversationId = "b"
        messages = longHistory(20) + assistantMessage("B 里最后一条")
        settle()
        assertTrue("换对话以后应该跟到新那段的最新一条：${viewport()}", tailVisible())

        // 在 B 里上翻：这一次必须记在 B 的状态上。
        userScrollsBack(pixels = 1_500f, durationMillis = 6_000)
        val firstBefore = measuredFirst()
        assertFalse("用户在 B 里上翻以后不该还算在底部：${viewport()}", atBottom())

        // B 里继续追加内容：视口一点都不许动。
        messages = messages + assistantMessage("B 里新到的一条")
        streaming = "B 里正在变长的回答"
        settle()
        assertEquals("B 里上翻后视口被新内容改了：${viewport()}", firstBefore, measuredFirst())
        assertFalse("新内容把用户拉回了底部：${viewport()}", atBottom())
    }

    /**
     * P2-2 复审 r2 第 6 条第二个窗口：第一次回答落库时，会话 ID 从 null 变成数据库 ID，那不是
     * 换会话。改前会被当成切换，把用户在这条长回答里的上翻状态重置掉，视口又被拉回底部。
     */
    @Test
    fun theFirstSavedAnswerKeepsAnUpScrolledViewport() {
        conversationId = null
        messages = emptyList()
        streaming = longAnswer(60)
        setContent()

        // 摆成「用户跟着这条长回答到了末尾」的样子：新对话里用户在落库前就已经在回答里上翻。
        rule.runOnIdle { listState.requestScrollToItem(0, 100_000) }
        settle()
        assertTrue("应该先停在长回答的末尾：${viewport()}", atBottom())
        userScrollsBack(pixels = 700f, durationMillis = 5_000)
        assertTrue("用户应该停在长回答中间：${viewport()}", listState.firstVisibleItemScrollOffset > 0)
        assertFalse("用户上翻以后不该还算在底部：${viewport()}", atBottom())

        // 第一次落库：会话 ID 从 null 变成真实 ID，回答同时也进了消息列表。这不是换会话。
        conversationId = "saved-1"
        messages = listOf(userMessage("第一问"), assistantMessage(longAnswer(60)))
        streaming = null
        settle()

        assertFalse("首次落库把用户的上翻状态重置了：${viewport()}", atBottom())
        assertFalse("首次落库以后视口被拉到了最新一条：${viewport()}", tailVisible())

        // 落库之后用户还在上翻：下一轮的内容一样不许把视口拉走。
        val firstAfter = measuredFirst()
        streaming = longAnswer(30, prefix = "第二轮在写")
        settle()
        assertEquals("首次落库后上翻状态没保持住：${viewport()}", firstAfter, measuredFirst())
    }

    /**
     * 换会话要重置，跟消息条数无关：改后跟滚动的协程按「这一段对话的身份」重新跑，条数一样也得
     * 跟到 B 的最新一条。
     */
    @Test
    fun switchingToAnotherConversationWithTheSameMessageCountFollowsTheNewTail() {
        conversationId = "a"
        messages = longHistory(20) + assistantMessage("A 的最后一条")
        setContent()
        assertTrue("一开始应该跟到底部：${viewport()}", atBottom())

        userScrollsBack(pixels = 1_500f, durationMillis = 6_000)
        val indexBefore = listState.firstVisibleItemIndex
        assertFalse("用户应该已经离开底部：${viewport()}", atBottom())

        // 换成另一段对话，消息条数一模一样。
        conversationId = "b"
        messages = longHistory(20) + assistantMessage("B 的最后一条")
        settle()

        assertNotEquals("换会话以后视口还停在 A 的位置：${viewport()}", indexBefore, listState.firstVisibleItemIndex)
        assertTrue("换会话以后应该跟到 B 的最新一条：${viewport()}", tailVisible())
        rule.onNodeWithText("B 的最后一条").assertIsDisplayed()
    }

    @Test
    fun newTokensAreFollowedWhileTheViewportIsAtTheBottom() {
        messages = longHistory(16)
        streaming = "第 1 句。"
        setContent()

        assertTrue("底部时应该停在内容末尾：${viewport()}", tailVisible())

        repeat(6) { step ->
            val before = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            streaming = buildString {
                append(streaming ?: "")
                append("\n第 ${step + 2} 句新内容。")
            }
            settle()
            assertTrue("底部时新 token 到达后没有跟到底：${viewport()}", tailVisible())
            val after = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            assertNotEquals("底部时新 token 没有跟着滚：${viewport()}", before, after)
        }
        rule.onNodeWithText("第 7 句新内容。", substring = true).assertIsDisplayed()
    }
}