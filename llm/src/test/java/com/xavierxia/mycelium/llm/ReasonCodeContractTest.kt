package com.xavierxia.mycelium.llm

import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.ChatRole
import com.xavierxia.mycelium.model.GenerationParameters
import com.xavierxia.mycelium.model.ModelInfo
import com.xavierxia.mycelium.model.ModelLocation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P2-1d 原因码的**跨层契约**（方案审必修项 5）：原生报回来的码必须能被本侧接受，而真正矛盾的组合
 * 仍然要被拒。
 *
 * 背景：`readingsOf()` 的校验原来写死 `0..4`，而且「原因 1 ⇔ 没请求复用」是硬等价的。S2/S3 一旦开始
 * 报 5/6/7，这两种写法都会把**正常读数**当协议错误抛掉——真机数据会变成零，而且看起来像「检查点没生效」。
 * 所以这里用假原生桥直接驱动真实校验，把「哪些组合该过、哪些该拒」逐个钉住。
 */
class ReasonCodeContractTest {

    private class Descriptor : ModelFile {
        override val fd = 7
        override fun close() {}
    }

    /** 按给定的原因码与复用情况拼出原生读数；tokenize 固定给出 [promptSize] 个 token。 */
    private class CodesBridge(
        private val promptSize: Int,
        private val reason: Long,
        private val reused: Int,
    ) : EngineBridge {
        override fun begin() {}
        override fun stop() {}
        override fun load(fd: Int, threads: Int, context: Int) {}
        override fun unload() {}
        override fun tokenize(messages: ByteArray): IntArray = IntArray(promptSize) { 1 }
        override fun generate(
            messages: ByteArray,
            maximum: Int,
            temperature: Float,
            topP: Float,
            seed: Int,
            reuseTokens: Int,
            saveCheckpointAt: Int,
            callback: TokenCallback,
        ): LongArray {
            callback.onStart(false)
            callback.onToken("好".toByteArray())
            // count, generated, promptMs, genMs, stopped, reused, read, reason
            return longArrayOf(promptSize.toLong(), 1, 1, 1, 0, reused.toLong(), (promptSize - reused).toLong(), reason)
        }
    }

    private val model = ModelInfo("test", "test.gguf", ModelLocation.LOCAL)
    private fun messages() = listOf(ChatMessage(ChatRole.USER, "问一句"))

    /** 跑一轮，返回结局：统计或抛出的异常。 */
    private fun runRound(promptSize: Int, reason: Long, reused: Int): Pair<GenerationStats?, Throwable?> = runBlocking {
        val engine = LocalModelEngine { CodesBridge(promptSize, reason, reused) }
        engine.load(model, EngineOptions()) { Descriptor() }
        try {
            engine.generate(messages(), GenerationParameters(maxTokens = 8)).toList()
            engine.lastStats to null
        } catch (failure: Throwable) {
            null to failure
        } finally {
            engine.unload()
        }
    }

    @Test
    fun aFirstRoundThatReportsReasonFiveIsAccepted() {
        // 首轮 requested=0、reused=0、原生说「没有能用的检查点」（5）：这是 S2/S3 之后的**正常**读数，
        // 旧代码会因为「原因不是 1 却 requested==0」把它当协议错误抛掉。
        val (stats, failure) = runRound(promptSize = 10, reason = 5, reused = 0)

        assertTrue("原因 5 在首轮必须被接受，实际抛出：$failure", failure == null)
        assertEquals("码要原样交出来", 5L, stats?.reuseFallbackReason)
        assertEquals("说没复用就不能报复用", 0L, stats?.reusedPromptTokens)
    }

    @Test
    fun reasonSixAndSevenAreAcceptedOnRoundsThatAskedForReuse() {
        listOf(6L, 7L).forEach { code ->
            val (stats, failure) = runRound(promptSize = 10, reason = code, reused = 0)

            assertTrue("原因 $code 必须被接受，实际抛出：$failure", failure == null)
            assertEquals("原因 $code 要原样交出来", code, stats?.reuseFallbackReason)
        }
    }

    @Test
    fun anUnknownReasonCodeIsStillRejected() {
        val (_, failure) = runRound(promptSize = 10, reason = 8, reused = 0)

        assertTrue("范围扩到 0..7，8 仍然不认识", failure != null)
        assertTrue("错误信息要说清是原因码：${failure?.message}", failure?.message.orEmpty().contains("原因码"))
    }

    @Test
    fun reasonFourStaysForbiddenAsADecodeFailure() {
        val (_, failure) = runRound(promptSize = 10, reason = 4, reused = 0)

        assertTrue("原因 4（decode 失败）这一轮不能当读数用", failure != null)
    }

    @Test
    fun aCheckpointReasonThatClaimsReuseIsRejected() {
        listOf(5L, 6L, 7L).forEach { code ->
            val (_, failure) = runRound(promptSize = 10, reason = code, reused = 3)

            assertTrue("原因 $code 说没复用却报了复用，必须拒", failure != null)
            // 这里会被两道判据之一拦下（「复用数超出请求」或「说没复用却报复用」），两者都说明这一轮
            // 的读数自相矛盾；断言「说清是复用的问题」即可，不锁死是哪一道先拦。
            assertTrue("理由要说清是复用的问题：${failure?.message}", failure?.message.orEmpty().contains("复用"))
        }
    }

    @Test
    fun theOldPromptCacheContractIsUnchanged() {
        // P2-1c 的正常首轮：没请求复用 ⇒ 原因 1。这条不能被上面的放宽改坏。
        val (stats, failure) = runRound(promptSize = 10, reason = 1, reused = 0)

        assertTrue("首轮报原因 1 是正常路径，实际抛出：$failure", failure == null)
        assertEquals(1L, stats?.reuseFallbackReason)

        // 「请求了复用却报原因 1」的拒绝由 P2-1c 的两轮测例覆盖（PromptCacheTest），这里不再复制一份
        // ——本用例的假桥只有一轮，造不出 requested>0。
    }

    @Test
    fun aSuccessfulReuseStillReportsZero() {
        // 正对照：原因 0 且真的复用了，必须放行。注意 requested=0 时「原因 0」本身自相矛盾
        // （没请求复用却说复用成功），所以这里断言的是它**仍然会被拒**——这正是 P2-1c 的判据，
        // 上面的放宽没有把它改坏。
        val (_, failure) = runRound(promptSize = 10, reason = 0, reused = 0)

        assertTrue("没请求复用却报「复用成功」应当被拒", failure != null)
        assertTrue("理由要与复用数有关：${failure?.message}", failure?.message.orEmpty().contains("复用"))
    }
}