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
    // 上层的会话段身份（P2-2 复审 r3 第 5 条）：每次导航 +1，落库不动它。
    private var conversationSegment: Long by mutableStateOf(0L)

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
                conversationSegment = conversationSegment,
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

    /**
     * 上层的导航：换到另一段对话，会话段身份 +1（真实调用点是 HearthSpace 里的 `navigate`，打开历史、
     * 新对话、切模式都走它）。首次落库不是导航，段身份不动。
     *
     * 导航之后在这里等重组和自动滚动落定。这一步不能省：只改状态、不等的话，紧接着读
     * `listState.layoutInfo` 读到的是导航**之前**那一帧（`total` 还是旧列表），
     * `openingSavedHistoryFromAnUnsavedStreamingConversationFollowsTheNewTail` 就是这样假红过一次
     * （断言看见 `total=1`、`lastSize=3952` —— 那条还没被换掉的流式长回答）。
     */
    private fun navigateTo(id: String?, nextMessages: List<HearthMessage>, nextStreaming: String? = null) {
        conversationSegment += 1L
        conversationId = id
        messages = nextMessages
        streaming = nextStreaming
        settle()
    }

    /**
     * 用户往回想（内容跟着手指往下走）一个大半屏的距离。起止点按控件自己的高度算，不用固定像素：
     * 换设备、换字号、换屏幕尺寸都还是「从上半屏拖到下半屏」，不会因为 1500px 在矮屏上出界而
     * 只滑掉一点点。
     */
    private fun userScrollsBackAcrossTheScreen(durationMillis: Long) {
        touch().performTouchInput {
            swipeDown(startY = top + height * 0.15f, endY = bottom - height * 0.15f, durationMillis = durationMillis)
        }
        settle()
    }

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

        // 回到前面的消息：拖的距离按控件高度算，不写死像素（见 userScrollsBackAcrossTheScreen）。
        userScrollsBackAcrossTheScreen(durationMillis = 6_000)
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

        navigateTo("b", longHistory(20) + assistantMessage("B 里最后一条"))
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
        navigateTo("b", longHistory(20) + assistantMessage("B 里最后一条"))
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
     *
     * P2-2 复审 r3 第 5 条后判据换成会话段身份：落库**不动**段序号，所以这里全程不碰
     * [conversationSegment]；换成 r3 第 5 条那种「未保存的新对话 → 已有历史 B」的导航时，
     * 上层会把段序号 +1（见 openingSavedHistoryFromAnUnsavedStreamingConversationFollowsTheNewTail）。
     */
    @Test
    fun theFirstSavedAnswerInTheSameSegmentKeepsAnUpScrolledViewport() {
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

        // 第一次落库：会话 ID 从 null 变成真实 ID，回答同时也进了消息列表。段序号不动，这不是换会话。
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
     * P2-2 复审 r3 第 5 条（新回归）：未保存的新对话里，首轮长回答还在流式生成、用户已经上翻，
     * 这时打开一段已有历史 B。旧判据只看「原 ID 是 null、原消息列表为空」，把这次导航也当成
     * 首次落库，于是保留 followTail=false，B 的最新内容不再自动跟随。
     *
     * 断言：段身份 +1（导航）以后必须重置——视口跟到 B 的末尾（`tailVisible()`），B 里后来
     * 到达的内容也继续自动跟随。
     *
     * `navigateTo` 里已经把重组和自动滚动等落定（这里以前没等，读到的是导航前那一帧的
     * `layoutInfo`，在真机上假红过一次 —— 见 `navigateTo` 的注释）。
     */
    @Test
    fun openingSavedHistoryFromAnUnsavedStreamingConversationFollowsTheNewTail() {
        conversationId = null
        messages = emptyList()
        streaming = longAnswer(60)
        setContent()

        // 摆成「用户在还没落库的新对话里、跟着这条长回答上翻」的样子。
        rule.runOnIdle { listState.requestScrollToItem(0, 100_000) }
        settle()
        userScrollsBack(pixels = 700f, durationMillis = 5_000)
        assertFalse("用户上翻以后不该还在底部：${viewport()}", atBottom())

        // 打开已有历史 B：这是导航（上层段身份 +1），不是这段新对话的首次落库。
        navigateTo("b", longHistory(20) + assistantMessage("B 的最后一条"))

        assertTrue("打开已有历史 B 以后必须跟到 B 的末尾：${viewport()}", tailVisible())
        rule.onNodeWithText("B 的最后一条").assertIsDisplayed()

        // B 里后来的内容也要继续跟着走：旧判定会让这里停在原地上不动。
        messages = messages + assistantMessage("B 里又到一条")
        settle()
        assertTrue("B 里的新内容没有自动跟随：${viewport()}", tailVisible())
        rule.onNodeWithText("B 里又到一条").assertIsDisplayed()
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
        navigateTo("b", longHistory(20) + assistantMessage("B 的最后一条"))

        assertNotEquals("换会话以后视口还停在 A 的位置：${viewport()}", indexBefore, listState.firstVisibleItemIndex)
        assertTrue("换会话以后应该跟到 B 的最新一条：${viewport()}", tailVisible())
        rule.onNodeWithText("B 的最后一条").assertIsDisplayed()
    }

    /**
     * 兜底：上层漏给新段序号时，真实 ID 之间的切换照样算导航——数据库里的落库只会把 null 写成
     * 真实 ID，不会把一个真实 ID 换成另一个，所以这不是「首次落库」。
     */
    @Test
    fun switchingConversationIdsWithinTheSameSegmentStillResets() {
        conversationId = "a"
        messages = longHistory(20) + assistantMessage("A 的最后一条")
        setContent()
        assertTrue("一开始应该跟到底部：${viewport()}", atBottom())

        userScrollsBack(pixels = 1_500f, durationMillis = 6_000)
        assertFalse("用户应该已经离开底部：${viewport()}", atBottom())

        // 段序号故意不动，只把 ID 换成另一个真实 ID（A → B）。
        conversationId = "b"
        messages = longHistory(20) + assistantMessage("B 的最后一条")
        settle()

        assertTrue("同一段里换到另一个真实 ID 没有重置：${viewport()}", tailVisible())
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