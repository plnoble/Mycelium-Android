package com.xavierxia.mycelium.llm

import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.ChatRole
import com.xavierxia.mycelium.model.ModelInfo
import com.xavierxia.mycelium.model.ModelLocation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-1c 的 E（统筹 3.1 裁定）：隐藏思考段用了多少 token、等了多久，只记进引擎统计与日志，不进界面，
 * **不改生成内容**。
 *
 * 口径（写进交付报告）：原生每来一个 token，先看过滤器当时是不是在隐藏段里，再决定算不算隐藏 token；
 * 因此开标签、闭标签那两个 token 都算隐藏（它们至少有一部分是隐藏的）。耗时从这一轮第一个原生 token
 * 算到第一段非空白正文出现为止，不含读提示、模板与加载。
 */
class HiddenSpanStatsTest {

    private class Descriptor : ModelFile {
        override val fd = 7
        override fun close() {}
    }

    /**
     * 按给定的片段逐个回调的假原生层。[startHidden] 就是原生 `onStart(thinking)` 那一句，
     * 也是「这一轮的提示以思考段开场」这一事实的来源。
     */
    private class SpanBridge(
        private val pieces: List<String>,
        private val startHidden: Boolean,
        private val failAfter: Int? = null,
    ) : EngineBridge {
        override fun begin() {}
        override fun stop() {}
        override fun load(fd: Int, threads: Int, context: Int) {}
        override fun unload() {}
        override fun tokenize(messages: ByteArray): IntArray = IntArray(1)

        override fun generate(
            messages: ByteArray,
            maximum: Int,
            temperature: Float,
            topP: Float,
            seed: Int,
            reuseTokens: Int,
            callback: TokenCallback,
        ): LongArray {
            callback.onStart(startHidden)
            pieces.forEachIndexed { index, piece ->
                if (failAfter != null && index >= failAfter) throw IllegalStateException("假原生：这一轮失败了")
                callback.onToken(piece.toByteArray())
            }
            if (failAfter != null && failAfter <= pieces.size) throw IllegalStateException("假原生：这一轮失败了")
            return longArrayOf(1, pieces.size.toLong(), 1, 1, 0, 0, 1, 1)
        }
    }

    /** 过滤器用的收尾标签（`<` + `/think` + `>`）。拼接写出来，免得被工具链的显示层吃掉。 */
    private val closeTag = "<" + "/think" + ">"

    private val model = ModelInfo("hidden-span-fake", "fake.gguf", ModelLocation.LOCAL)

    private suspend fun engineWith(bridge: SpanBridge): LocalModelEngine {
        val engine = LocalModelEngine { bridge }
        engine.load(model, EngineOptions()) { Descriptor() }
        return engine
    }

    @Test
    fun aSpanThatEndsInVisibleTextIsCountedAndTimed() = runBlocking {
        val bridge = SpanBridge(
            listOf("先想", "一下" + closeTag.take(5), closeTag.drop(5), "正文"),
            startHidden = true,
        )
        val engine = engineWith(bridge)

        val text = engine.generate(listOf(ChatMessage(ChatRole.USER, "问"))).toList().joinToString("")

        assertEquals("隐藏段不能漏进正文，正文也不能被吃掉", "正文", text)
        val stats = checkNotNull(engine.lastStats)
        assertEquals("前三个 token 都在隐藏段里（含收尾标签那个）", 3L, stats.hiddenTokens)
        assertTrue("隐藏段耗时不该是负数", stats.hiddenMillis >= 0)
        // 既有口径不动：生成的 token 数还是整轮的原生 token 数，正文只是被过滤掉了一部分。
        assertEquals("生成 token 数仍是原生报的那个", 4L, stats.generatedTokens)
    }

    @Test
    fun aRoundThatStartsWithVisibleTextRecordsNoHiddenSpan() = runBlocking {
        val bridge = SpanBridge(listOf("好"), startHidden = false)
        val engine = engineWith(bridge)

        val text = engine.generate(listOf(ChatMessage(ChatRole.USER, "问"))).toList().joinToString("")

        assertEquals("好", text)
        val stats = checkNotNull(engine.lastStats)
        assertEquals("第一个 token 就是正文，不该记隐藏段", 0L, stats.hiddenTokens)
        assertEquals("没有隐藏段就没有等待", 0L, stats.hiddenMillis)
    }

    @Test
    fun anUnclosedSpanIsCountedAsHiddenAndStaysOutOfTheBody() = runBlocking {
        val bridge = SpanBridge(listOf("秘密", "还在想"), startHidden = true)
        val engine = engineWith(bridge)

        val text = engine.generate(listOf(ChatMessage(ChatRole.USER, "问"))).toList().joinToString("")

        assertEquals("整段都没闭合，正文必须为空", "", text)
        val stats = checkNotNull(engine.lastStats)
        assertEquals("两个 token 都算隐藏", 2L, stats.hiddenTokens)
        assertTrue("一直没出正文，耗时应记到这一轮结束", stats.hiddenMillis >= 0)
    }

    @Test
    fun aFailedRoundStillReportsTheHiddenWorkItAlreadyDid() = runBlocking {
        // 先吐出两个隐藏 token，再抛错：这一轮没有原生读数，但隐藏段的账不能丢。
        val bridge = SpanBridge(listOf("秘密", "还在想", "不该到这里"), startHidden = true, failAfter = 2)
        val engine = engineWith(bridge)

        val failure = runCatching {
            engine.generate(listOf(ChatMessage(ChatRole.USER, "问"))).toList()
        }

        assertTrue("原生抛错就该失败", failure.isFailure)
        assertNotNull("失败之后也要留下这一轮的统计", engine.lastStats)
        val stats = checkNotNull(engine.lastStats)
        assertEquals("已经产生的两个隐藏 token 要记下来", 2L, stats.hiddenTokens)
        // 这一轮没走到原生读数：统计必须标明「读数不完整」，而不是把未知的 token/耗时写成 0
        // 再当作测出来的值（全量审必修 6）。
        assertTrue("读数不完整要标明，不能冒充测出来的值", !stats.nativeReadingsComplete)
    }
}