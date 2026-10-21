package com.xavierxia.mycelium.llm

import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.ChatRole
import com.xavierxia.mycelium.model.GenerationParameters
import com.xavierxia.mycelium.model.ModelInfo
import com.xavierxia.mycelium.model.ModelLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2-1c 第 1 步：引擎记住上一轮完整读完的那段提示，下一轮只按最长公共前缀裁剪、读剩下的新 token。
 *
 * 这里替掉的 [EngineBridge] 就是方案说的「假原生层」：它按同一套决定论把提示字节逐个当成 token，
 * 于是「前缀相同」在 token 上也一定相同；每一轮收到多少复用、实际读了多少，全都由 JVM 侧决定并
 * 可在此断言。真原生只负责按数量裁剪 KV、只读剩下的部分（`engine.cpp`，JVM 测例结构上覆盖不到，
 * 由真机用例兜底）。
 */
class PromptCacheTest {

    private class Descriptor : ModelFile {
        override val fd = 7
        override fun close() {}
    }

    /**
     * 把提示字节逐个当 token 的假原生层。它记下每一轮的 token 序列与收到的复用数，并按 P2-1c 的
     * 7 项契约回读数：{整段 token、生成 token、读提示毫秒、生成毫秒、是否停止、复用、实读}。
     */
    private class FakeBridge : EngineBridge {
        /** 每一轮 tokenize 出来的序列，按调用顺序。 */
        val prompts = mutableListOf<IntArray>()

        /** 每一轮原生收到的复用数，按调用顺序。 */
        val reuses = mutableListOf<Int>()

        /**
         * 假原生层自己保存的 KV 状态：上一次**真的读完**的那段 token。它和引擎各算一次公共前缀，
         * 但只有这份状态代表「KV 里其实有什么」，所以引擎报的复用数必须能被它兑出来。
         */
        var kvState: IntArray? = null

        /** 引擎声称复用了 KV 里其实没有的东西时的记录（应当一直为空）。 */
        val overClaims = mutableListOf<String>()

        /** 让下一轮在提示读完之前收场：实读数比剩余部分少这么多（模拟读到一半被停）。 */
        var shortBy = 0

        /** 下一轮读数里的「是否停止」。 */
        var cancelled = false

        /** 下一轮原生直接抛错。 */
        var failNext = false

        /** 下一轮的生成卡在这里，直到 [stop] 被调用（模拟「生成中被取消」）。 */
        var holdGeneration = false

        /** 已经进入生成的轮次数，用来在测例里等它真的进来。 */
        val entered = AtomicInteger()

        private var generationGate: CountDownLatch? = null

        override fun begin() {}
        override fun stop() { generationGate?.countDown() }
        override fun load(fd: Int, threads: Int, context: Int) { kvState = null }
        override fun unload() { kvState = null }

        override fun tokenize(messages: ByteArray): IntArray = IntArray(messages.size) { messages[it].toInt() }

        override fun generate(
            messages: ByteArray,
            maximum: Int,
            temperature: Float,
            topP: Float,
            seed: Int,
            reuseTokens: Int,
            callback: TokenCallback,
        ): LongArray {
            if (failNext) {
                failNext = false
                throw IllegalStateException("假原生：这一轮失败了")
            }
            val tokens = tokenize(messages)
            prompts += tokens
            reuses += reuseTokens

            // 核对「前 reuseTokens 个已经在 KV 里」这句话：假原生层拿自己的状态说话。
            val held = kvState ?: IntArray(0)
            if (reuseTokens > held.size || !held.copyOfRange(0, reuseTokens).contentEquals(tokens.copyOfRange(0, reuseTokens))) {
                overClaims += "第 ${prompts.size} 轮声称复用了 $reuseTokens 个，但 KV 里只有 ${held.size} 个可用"
            }

            val gate = if (holdGeneration) CountDownLatch(1).also { generationGate = it } else null
            holdGeneration = false
            val read = (tokens.size - reuseTokens - shortBy).coerceAtLeast(0)
            shortBy = 0
            val stopped = cancelled
            cancelled = false
            callback.onStart(false)
            callback.onToken("好".toByteArray())
            entered.incrementAndGet()
            gate?.await()
            generationGate = null
            // 只有真的读完的那一轮才会成为 KV 的状态——这正是原生 promptComplete 的口径。
            if (read == tokens.size - reuseTokens) kvState = tokens
            return longArrayOf(
                tokens.size.toLong(),
                1L,
                7L,
                3L,
                if (stopped) 1L else 0L,
                reuseTokens.toLong(),
                read.toLong(),
                if (reuseTokens == 0) 1L else 0L,
            )
        }
    }

    /** 等一个条件成立（:llm 没有炉边那套 waitFor，自己轮询一小会儿）。 */
    private fun awaitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue("等待超时（${timeoutMs}ms）：$what", condition())
    }

    private val model = ModelInfo("p2-1c-fake", "fake.gguf", ModelLocation.LOCAL)

    private suspend fun engineWith(bridge: FakeBridge): LocalModelEngine {
        val engine = LocalModelEngine { bridge }
        engine.load(model, EngineOptions()) { Descriptor() }
        return engine
    }

    /** 两个 token 序列的公共前缀长度，与引擎自己算的是同一个定义。 */
    private fun sharedPrefix(a: IntArray, b: IntArray): Int {
        val limit = minOf(a.size, b.size)
        var shared = 0
        while (shared < limit && a[shared] == b[shared]) shared++
        return shared
    }

    private suspend fun LocalModelEngine.round(messages: List<ChatMessage>, key: String?): List<String> =
        generate(messages, GenerationParameters(cacheKey = key)).toList()

    @Test
    fun reusesTheCommonPrefixOfTheSamePrompt() = runBlocking {
        val bridge = FakeBridge()
        val engine = engineWith(bridge)
        val messages = listOf(ChatMessage(ChatRole.USER, "同一段提示"))

        engine.round(messages, "c1")
        engine.round(messages, "c1")

        assertEquals("第一轮没有可复用的缓存", 0, bridge.reuses[0])
        assertEquals(
            "第二轮要把上一轮读完的那段当作前缀，只留最后一个 token 来解",
            bridge.prompts[0].size - 1,
            bridge.reuses[1],
        )
        val stats = checkNotNull(engine.lastStats)
        assertEquals("复用数按原生回声记账", bridge.reuses[1].toLong(), stats.reusedPromptTokens)
        assertEquals(
            "实读数 = 整段 - 复用",
            (bridge.prompts[1].size - bridge.reuses[1]).toLong(),
            stats.readPromptTokens,
        )
        assertEquals("整段提示的长度不变", bridge.prompts[1].size.toLong(), stats.promptTokens)
    }

    @Test
    fun growsOnlyOverTheNewTail() = runBlocking {
        val bridge = FakeBridge()
        val engine = engineWith(bridge)
        val first = ChatMessage(ChatRole.USER, "第一问")
        engine.round(listOf(first), "c1")

        val grown = listOf(first, ChatMessage(ChatRole.ASSISTANT, "第一答"), ChatMessage(ChatRole.USER, "第二问"))
        engine.round(grown, "c1")

        val expected = sharedPrefix(bridge.prompts[0], bridge.prompts[1])
        assertTrue("两轮之间应有一段真实的公共前缀", expected > 0)
        assertTrue("新提示比上一轮长", bridge.prompts[1].size > bridge.prompts[0].size)
        assertEquals("复用数 = 真实公共前缀，而不是上一轮整段长度", expected, bridge.reuses[1])
        assertEquals(
            "只读新增的那一段",
            (bridge.prompts[1].size - expected).toLong(),
            checkNotNull(engine.lastStats).readPromptTokens,
        )
    }

    @Test
    fun neverReusesMoreThanTheCachedPrefix() = runBlocking {
        val bridge = FakeBridge()
        val engine = engineWith(bridge)
        engine.round(listOf(ChatMessage(ChatRole.USER, "第一问")), "c1")
        engine.round(listOf(ChatMessage(ChatRole.USER, "完全不同的一问")), "c1")

        val expected = sharedPrefix(bridge.prompts[0], bridge.prompts[1])
        assertEquals("换了内容就只能复用真正相同的那一段", expected, bridge.reuses[1])
        assertTrue("公共前缀必须短于缓存的整段", expected < bridge.prompts[0].size)
        assertEquals(
            "差了多少就读多少",
            (bridge.prompts[1].size - expected).toLong(),
            checkNotNull(engine.lastStats).readPromptTokens,
        )
    }

    @Test
    fun alwaysLeavesAtLeastOneTokenToDecode() = runBlocking {
        val bridge = FakeBridge()
        val engine = engineWith(bridge)
        val messages = listOf(ChatMessage(ChatRole.USER, "重复同一段"))

        repeat(3) { engine.round(messages, "c1") }

        assertEquals(3, bridge.reuses.size)
        assertEquals("第一轮没有缓存", 0, bridge.reuses[0])
        assertEquals("第二轮留最后一个 token", bridge.prompts[0].size - 1, bridge.reuses[1])
        assertEquals("第三轮同样", bridge.prompts[0].size - 1, bridge.reuses[2])
        assertTrue("每一轮都要真的解至少一个 token", bridge.prompts[0].size - bridge.reuses[2] >= 1)
        assertEquals("实读就是那最后一个 token", 1L, checkNotNull(engine.lastStats).readPromptTokens)
    }

    @Test
    fun promptMillisCountsOnlyTheReadPart() = runBlocking {
        val bridge = FakeBridge()
        val engine = engineWith(bridge)
        val messages = listOf(ChatMessage(ChatRole.USER, "计时口径"))

        engine.round(messages, "c1")
        val first = checkNotNull(engine.lastStats)
        assertEquals("第一轮整段都读了", first.promptTokens, first.readPromptTokens)
        assertEquals("第一轮没有可复用的", 0L, first.reusedPromptTokens)

        engine.round(messages, "c1")
        val second = checkNotNull(engine.lastStats)
        assertEquals("整段提示的长度不变", first.promptTokens, second.promptTokens)
        assertEquals("第二轮只有最后一个 token 真的读了", 1L, second.readPromptTokens)
        assertEquals("复用数 = 整段 - 实读", second.promptTokens - second.readPromptTokens, second.reusedPromptTokens)
        assertEquals("读提示毫秒按原生回的读数记（只量实际读的那段），不是整段耗时", 7L, second.promptMillis)
    }

    @Test
    fun aRoundWithoutAKeyGivesUpTheCacheSoTheNextKeyedRoundStartsFromScratch() = runBlocking {
        val bridge = FakeBridge()
        val engine = engineWith(bridge)
        val first = ChatMessage(ChatRole.USER, "第一问")
        engine.round(listOf(first), "c1")

        val grown = listOf(first, ChatMessage(ChatRole.ASSISTANT, "第一答"), ChatMessage(ChatRole.USER, "第二问"))

        // 不传键：不复用，整段重读。
        engine.round(grown, null)
        assertEquals("不传缓存键就不许复用", 0, bridge.reuses[1])
        assertEquals(
            "不传键的一轮整段都读",
            bridge.prompts[1].size.toLong(),
            checkNotNull(engine.lastStats).readPromptTokens,
        )

        // 再传回原来的键：那一轮把 KV 清空重读了，引擎手里的提示已经不再属于这个键，
        // 所以这一轮必须整段重读，不能拿旧归属去复用一个其实不属于它的前缀。
        engine.round(grown, "c1")
        assertEquals("无键的一轮之后，同一个键也得重新建缓存", 0, bridge.reuses[2])
    }

    @Test
    fun aDifferentCacheKeyStartsFromScratch() = runBlocking {
        val bridge = FakeBridge()
        val engine = engineWith(bridge)
        val messages = listOf(ChatMessage(ChatRole.USER, "换键的一问"))

        engine.round(messages, "c1")
        engine.round(messages, "c2")

        // 提示一模一样，只是键换了：这就是「换人物/换会话」那一类，必须整段重读。
        assertEquals(0, bridge.reuses[1])
        assertTrue("公共前缀本来是存在的，是键把它挡住了", sharedPrefix(bridge.prompts[0], bridge.prompts[1]) > 0)
        assertEquals(
            "整段都读",
            bridge.prompts[1].size.toLong(),
            checkNotNull(engine.lastStats).readPromptTokens,
        )
    }

    @Test
    fun aReloadStartsFromScratch() = runBlocking {
        val bridge = FakeBridge()
        val engine = engineWith(bridge)
        val messages = listOf(ChatMessage(ChatRole.USER, "重载的一问"))
        engine.round(messages, "c1")

        engine.load(model.copy(id = "another-model"), EngineOptions()) { Descriptor() }
        engine.round(messages, "c1")

        assertEquals("重新 load 之后不许再复用上一个模型的缓存", 0, bridge.reuses[1])
    }

    @Test
    fun anUnloadStartsFromScratch() = runBlocking {
        val bridge = FakeBridge()
        val engine = engineWith(bridge)
        val messages = listOf(ChatMessage(ChatRole.USER, "卸载的一问"))
        engine.round(messages, "c1")

        engine.unload()
        engine.load(model, EngineOptions()) { Descriptor() }
        engine.round(messages, "c1")

        assertEquals("卸载过之后不许再复用", 0, bridge.reuses[1])
    }

    @Test
    fun aTruncatedPromptReusesOnlyTheUnchangedPrefix() = runBlocking {
        val bridge = FakeBridge()
        val engine = engineWith(bridge)
        val sheet = ChatMessage(ChatRole.SYSTEM, "人物设定：这一段永远在最前面。")
        val first = ChatMessage(ChatRole.USER, "第一问")
        val answer = ChatMessage(ChatRole.ASSISTANT, "第一答")
        val second = ChatMessage(ChatRole.USER, "第二问")

        engine.round(listOf(sheet, first, answer, second), "c1")
        // 历史从最老的轮次开始丢：设定那段仍在最前，后面的重读。
        engine.round(listOf(sheet, second), "c1")

        val expected = sharedPrefix(bridge.prompts[0], bridge.prompts[1])
        assertEquals("截断后只能复用真正没变的那一段", expected, bridge.reuses[1])
        assertTrue("设定那一段应当还在公共前缀里", expected > 0)
        assertTrue("但必须短于上一轮的整段提示", expected < bridge.prompts[0].size)
    }

    @Test
    fun aStopBeforeThePromptIsReadInvalidatesTheCache() = runBlocking {
        val bridge = FakeBridge()
        val engine = engineWith(bridge)
        val messages = listOf(ChatMessage(ChatRole.USER, "读一半被停"))
        engine.round(messages, "c1")

        // 这一轮：提示没读完（实读比剩余少 5），而且原生说它被停了。
        bridge.shortBy = 5
        bridge.cancelled = true
        engine.round(messages, "c1")

        engine.round(messages, "c1")
        assertEquals("提示没读完就被停，缓存必须作废", 0, bridge.reuses[2])
    }

    @Test
    fun aStopDuringGenerationKeepsTheCacheUsable() = runBlocking {
        val bridge = FakeBridge()
        val engine = engineWith(bridge)
        val messages = listOf(ChatMessage(ChatRole.USER, "生成中被停"))
        engine.round(messages, "c1")

        // 提示读完了，只是生成中途停了：KV 里的提示是完整的，下一轮仍然可以复用。
        bridge.cancelled = true
        engine.round(messages, "c1")

        engine.round(messages, "c1")
        assertEquals(
            "提示已经读完，生成中途停止不该作废缓存",
            bridge.prompts[0].size - 1,
            bridge.reuses[2],
        )
    }

    @Test
    fun aFailureInvalidatesTheCache() = runBlocking {
        val bridge = FakeBridge()
        val engine = engineWith(bridge)
        val messages = listOf(ChatMessage(ChatRole.USER, "原生失败"))
        engine.round(messages, "c1")

        bridge.failNext = true
        val failure = runCatching { engine.round(messages, "c1") }
        assertTrue("原生抛错就该失败", failure.isFailure)

        engine.round(messages, "c1")
        assertEquals("抛错的那一轮根本没进假原生层（它在最开头就抛了）", 2, bridge.reuses.size)
        assertEquals("原生失败之后不许再复用", 0, bridge.reuses.last())
    }

    @Test
    fun cancellingTheCollectorInvalidatesTheCache() = runBlocking {
        val bridge = FakeBridge()
        val engine = engineWith(bridge)
        val messages = listOf(ChatMessage(ChatRole.USER, "取消收集"))
        engine.round(messages, "c1")

        // 这一轮停在生成里：取消收集方，引擎必须在收尾时作废缓存（原生要等 worker 退出）。
        bridge.holdGeneration = true
        val job = launch(Dispatchers.Default) {
            runCatching { engine.round(messages, "c1") }
        }
        awaitUntil("这一轮已经进入生成") { bridge.entered.get() == 2 }
        withTimeout(5_000) { job.cancelAndJoin() }

        engine.round(messages, "c1")
        assertEquals("取消之后同键也不许复用", 0, bridge.reuses[2])
    }

    @Test
    fun theEngineNeverClaimsToHaveReadMoreThanTheCacheHolds() = runBlocking {
        val bridge = FakeBridge()
        val engine = engineWith(bridge)
        val sheet = ChatMessage(ChatRole.SYSTEM, "人物设定。")
        val first = ChatMessage(ChatRole.USER, "第一问")
        val answer = ChatMessage(ChatRole.ASSISTANT, "第一答")
        val second = ChatMessage(ChatRole.USER, "第二问")

        engine.round(listOf(sheet, first), "c1")
        engine.round(listOf(sheet, first, answer, second), "c1")
        engine.round(listOf(sheet, second), "c1")
        engine.round(listOf(sheet, first), "c1")

        assertTrue(
            "引擎报的复用数必须能被假原生层自己的 KV 状态兑出来：${bridge.overClaims}",
            bridge.overClaims.isEmpty(),
        )
    }
}