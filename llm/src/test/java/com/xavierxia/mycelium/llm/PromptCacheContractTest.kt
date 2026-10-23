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
import org.junit.Test

/**
 * P2-1c 全量审必修 4、6：把「读数契约」和「前缀决策」钉在**固定 token 向量**上。
 *
 * 这里不按提示字节造 token，而是让假桥按轮次直接给出 token 序列与读数数组，所以能造出真实多轮
 * 对话里很难凑出的形状：零公共前缀、中间分歧、提示变短、以及各种**坏读数**（少一项、长度对不上、
 * 复用超出请求、没停止却没读完、原因码不认识）。坏读数一律必须让这一轮失败，而不是被当成
 * 「这一轮没复用」。
 */
class PromptCacheContractTest {

    private class Descriptor : ModelFile {
        override val fd = 7
        override fun close() {}
    }

    /** 按轮次给出 token 序列与原生读数的假桥。 */
    private class ScriptedBridge(
        private val vectors: List<IntArray>,
        private val readings: List<LongArray>,
    ) : EngineBridge {
        private var round = 0
        val requested = mutableListOf<Int>()

        override fun begin() {}
        override fun stop() {}
        override fun load(fd: Int, threads: Int, context: Int) {}
        override fun unload() {}
        override fun tokenize(messages: ByteArray): IntArray = vectors[round]

        override fun generate(
            messages: ByteArray,
            maximum: Int,
            temperature: Float,
            topP: Float,
            seed: Int,
            reuseTokens: Int,
            saveCheckpointAt: Int,
            restoredTo: Int,
            callback: TokenCallback,
        ): LongArray {
            requested += reuseTokens
            val values = readings[round]
            round++
            callback.onStart(false)
            return values
        }
    }

    private val model = ModelInfo("contract-fake", "fake.gguf", ModelLocation.LOCAL)

    private suspend fun engineWith(bridge: ScriptedBridge): LocalModelEngine {
        val engine = LocalModelEngine { bridge }
        engine.load(model, EngineOptions()) { Descriptor() }
        return engine
    }

    private suspend fun LocalModelEngine.round(key: String? = "c1") =
        generate(listOf(ChatMessage(ChatRole.USER, "问")), GenerationParameters(cacheKey = key)).toList()

    private fun ok(count: Int, reused: Int, read: Int, stopped: Int = 0, reason: Int = if (reused == 0) 1 else 0) =
        longArrayOf(count.toLong(), 1, 7, 3, stopped.toLong(), reused.toLong(), read.toLong(), reason.toLong())

    @Test
    fun aReadingArrayThatIsNotEightOrTenItemsFailsTheRound() = runBlocking {
        // P2-1d 把读数从 8 项扩到 10 项（加了检查点位置与字节数）。9 项既不是旧格式也不是新格式，
        // 必须仍然被拒——否则「多半项」会被当成「没有检查点」悄悄放过。
        val engine = engineWith(
            ScriptedBridge(listOf(IntArray(4)), listOf(longArrayOf(4, 1, 7, 3, 0, 0, 4, 0, 11))),
        )

        val failure = runCatching { engine.round() }

        assertTrue("少一项就必须失败，实际 $failure", failure.isFailure)
        assertTrue(
            "失败原因要说清是几项：${failure.exceptionOrNull()?.message}",
            failure.exceptionOrNull()?.message.orEmpty().contains("8 或 10 项"),
        )
    }

    @Test
    fun aTenItemReadingArrayIsAcceptedAndItsCheckpointFieldsAreRead() = runBlocking {
        // 新格式：第 9、10 项是检查点位置与字节数。
        val engine = engineWith(
            ScriptedBridge(listOf(IntArray(4)), listOf(longArrayOf(4, 1, 7, 3, 0, 0, 4, 1, 33, 4_096))),
        )

        engine.round()

        assertEquals("位置要读出来", 33, engine.lastStats?.checkpointPos)
        assertEquals("字节数要读出来", 4_096L, engine.lastStats?.checkpointBytes)
    }

    @Test
    fun aPromptLengthThatDisagreesWithTokenizeFailsTheRound() = runBlocking {
        val engine = engineWith(ScriptedBridge(listOf(IntArray(4)), listOf(ok(count = 5, reused = 0, read = 4))))

        val failure = runCatching { engine.round() }

        assertTrue("长度对不上就必须失败，实际 $failure", failure.isFailure)
        assertTrue("失败原因要说清长度：$failure", failure.exceptionOrNull()?.message.orEmpty().contains("对不上"))
    }

    @Test
    fun aBridgeThatClaimsMoreReuseThanThisRoundAskedForFails() = runBlocking {
        val engine = engineWith(
            ScriptedBridge(
                vectors = listOf(IntArray(4) { it }, IntArray(4) { it }),
                readings = listOf(ok(count = 4, reused = 0, read = 4), ok(count = 4, reused = 4, read = 0)),
            ),
        )
        engine.round()

        val failure = runCatching { engine.round() }

        // 这一轮最多只能复用 3 个（总数 4 至少留一个要解），报 4 就必须失败。
        assertTrue("超出请求上限就必须失败，实际 $failure", failure.isFailure)
        assertTrue("失败原因要给出上线：$failure", failure.exceptionOrNull()?.message.orEmpty().contains("上限"))
    }

    @Test
    fun aRoundThatDidNotFinishThePromptWithoutStoppingFails() = runBlocking {
        val engine = engineWith(
            ScriptedBridge(
                vectors = listOf(IntArray(4) { it }, IntArray(4) { it }),
                readings = listOf(ok(count = 4, reused = 0, read = 4), ok(count = 4, reused = 3, read = 0)),
            ),
        )
        engine.round()

        val failure = runCatching { engine.round() }

        assertTrue("没停止却没读完就必须失败，实际 $failure", failure.isFailure)
        assertTrue("失败原因要说清没读完：$failure", failure.exceptionOrNull()?.message.orEmpty().contains("没把提示读完"))
    }

    @Test
    fun anUnknownFallbackReasonFails() = runBlocking {
        val engine = engineWith(ScriptedBridge(listOf(IntArray(4)), listOf(ok(count = 4, reused = 0, read = 4, reason = 9))))

        val failure = runCatching { engine.round() }

        assertTrue("原因码不认识就必须失败，实际 $failure", failure.isFailure)
        assertTrue("失败原因要带上原因码：$failure", failure.exceptionOrNull()?.message.orEmpty().contains("原因码"))
    }

    @Test
    fun zeroCommonPrefixReusesNothing() = runBlocking {
        val bridge = ScriptedBridge(
            vectors = listOf(intArrayOf(1, 2, 3), intArrayOf(9, 8, 7)),
            readings = listOf(ok(count = 3, reused = 0, read = 3), ok(count = 3, reused = 0, read = 3)),
        )
        val engine = engineWith(bridge)
        engine.round()
        engine.round()

        assertEquals("两轮都不该请求复用", listOf(0, 0), bridge.requested)
        assertEquals("完全没有公共前缀：第二轮一个都不许复用", 0, engine.lastStats!!.reusedPromptTokens)
        assertEquals("整段都读", 3L, engine.lastStats!!.readPromptTokens)
    }

    @Test
    fun aDivergenceInTheMiddleReusesOnlyThePrefix() = runBlocking {
        val bridge = ScriptedBridge(
            vectors = listOf(intArrayOf(1, 2, 3, 4), intArrayOf(1, 2, 9, 9, 9)),
            readings = listOf(ok(count = 4, reused = 0, read = 4), ok(count = 5, reused = 2, read = 3)),
        )
        val engine = engineWith(bridge)
        engine.round()
        engine.round()

        assertEquals("第二轮请求的就是公共前缀两个 token", listOf(0, 2), bridge.requested)
        assertEquals("只能复用前两个 token", 2, engine.lastStats!!.reusedPromptTokens)
        assertEquals("剩下的三个都要读", 3L, engine.lastStats!!.readPromptTokens)
    }

    @Test
    fun aShortenedPromptStillLeavesOneTokenToRead() = runBlocking {
        val engine = engineWith(
            ScriptedBridge(
                vectors = listOf(intArrayOf(1, 2, 3, 4), intArrayOf(1, 2)),
                readings = listOf(ok(count = 4, reused = 0, read = 4), ok(count = 2, reused = 1, read = 1)),
            ),
        )
        engine.round()
        engine.round()

        // 公共前缀是 2，但这一轮至少要解一个 token，所以只能复用 1。
        assertEquals("变短的提示仍要留一个 token 去解", 1, engine.lastStats!!.reusedPromptTokens)
        assertEquals("另一个 token 要读", 1L, engine.lastStats!!.readPromptTokens)
    }

    @Test
    fun aStopBitThatIsNeitherZeroNorOneFails() = runBlocking {
        val engine = engineWith(
            ScriptedBridge(listOf(IntArray(4)), listOf(ok(count = 4, reused = 0, read = 4).also { it[4] = 2 })),
        )

        val failure = runCatching { engine.round() }

        assertTrue("停止位不认识就必须失败，实际 $failure", failure.isFailure)
        assertTrue("失败原因要提到停止位：$failure", failure.exceptionOrNull()?.message.orEmpty().contains("停止位"))
    }

    @Test
    fun aReasonThatContradictsTheRequestFails() = runBlocking {
        val engine = engineWith(
            ScriptedBridge(
                vectors = listOf(IntArray(4) { it }, IntArray(4) { it }),
                readings = listOf(ok(count = 4, reused = 0, read = 4), ok(count = 4, reused = 0, read = 4, reason = 1)),
            ),
        )
        engine.round()

        val failure = runCatching { engine.round() }

        assertTrue("第二轮明明请求了复用却报「没人要」，必须失败，实际 $failure", failure.isFailure)
        assertTrue("失败原因要提到原因码：$failure", failure.exceptionOrNull()?.message.orEmpty().contains("原因码"))
    }

    @Test
    fun aReasonOfNothingShareableWhileClaimingReuseFails() = runBlocking {
        val engine = engineWith(
            ScriptedBridge(
                vectors = listOf(IntArray(4) { it }, IntArray(4) { it }),
                readings = listOf(ok(count = 4, reused = 0, read = 4), ok(count = 4, reused = 2, read = 2, reason = 2)),
            ),
        )
        engine.round()

        val failure = runCatching { engine.round() }

        assertTrue("原因说没得复用却报了复用，必须失败，实际 $failure", failure.isFailure)
    }

    @Test
    fun generatedTokensBeyondThisRoundsBudgetFail() = runBlocking {
        val engine = engineWith(
            ScriptedBridge(listOf(IntArray(4)), listOf(ok(count = 4, reused = 0, read = 4).also { it[1] = 100_000 })),
        )

        val failure = runCatching { engine.round() }

        assertTrue("超过这一轮预算就必须失败，实际 $failure", failure.isFailure)
        assertTrue("失败原因要提到预算：$failure", failure.exceptionOrNull()?.message.orEmpty().contains("预算"))
    }
}
