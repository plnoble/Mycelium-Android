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

        override fun begin() {}
        override fun stop() {}
        override fun load(fd: Int, threads: Int, context: Int) {}
        override fun unload() {}

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
            val tokens = tokenize(messages)
            prompts += tokens
            reuses += reuseTokens
            val read = (tokens.size - reuseTokens).coerceAtLeast(0)
            callback.onStart(false)
            callback.onToken("好".toByteArray())
            return longArrayOf(
                tokens.size.toLong(),
                1L,
                7L,
                3L,
                0L,
                reuseTokens.toLong(),
                read.toLong(),
                if (reuseTokens == 0) 1L else 0L,
            )
        }
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
}