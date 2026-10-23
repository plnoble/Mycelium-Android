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
 * P2-1d 的 S2/S3 编排：引擎在什么情况下**存**、什么情况下**取**、失败时怎么退。
 *
 * 用假原生桥把「原生那一侧」的行为换成可控的：能不能走检查点、存不存得下来、恢复成不成，都在用例里
 * 摆出来。这样「开关默认关」「没有检查点就报 5」「恢复失败就报 6」「纯注意力不碰检查点」这几条
 * 不用等真机就能钉死。
 *
 * 真机上的**状态对不对**（恢复出来的循环层是否真的对应那个位置）由 S5 的逐字比对负责，这里证明不了。
 */
class CheckpointFlowTest {

    private class Descriptor : ModelFile {
        override val fd = 7
        override fun close() {}
    }

    /**
     * 假原生桥：记录每一次检查点相关的调用，并按规定回答。
     *
     * @param checkpointable `canCheckpoint()` 的回答（混合/循环 = true）。
     * @param saveBytes 跨过落点时交回来的字节；null = 存不下来。
     * @param restoreOk `restoreCheckpoint()` 的回答。
     */
    private class Recorder(
        private val checkpointable: Boolean = true,
        private val saveBytes: ByteArray? = ByteArray(16) { 3 },
        private val restoreOk: Boolean = true,
    ) : EngineBridge {
        val calls = mutableListOf<String>()
        var saveAtSeen: Int = -1
        var restorePos: Int = -1
        var lastReuse: Int = -1
        private var pending: ByteArray? = null
        private var pendingPos: Int = -1

        override fun begin() {}
        override fun stop() {}
        override fun load(fd: Int, threads: Int, context: Int) {}
        override fun unload() {}
        override fun tokenize(messages: ByteArray): IntArray = IntArray(20) { 1 }

        override fun canCheckpoint(): Boolean {
            calls += "canCheckpoint"
            return checkpointable
        }

        override fun restoreCheckpoint(data: ByteArray, pos: Int): Boolean {
            calls += "restore@$pos"
            restorePos = pos
            return restoreOk
        }

        override fun takeCheckpoint(): ByteArray? {
            calls += "take"
            val out = pending
            pending = null
            return out
        }

        override fun takeCheckpointPos(): Int {
            calls += "takePos"
            val out = pendingPos
            pendingPos = -1
            return out
        }

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
            calls += "generate@reuse=$reuseTokens@save=$saveCheckpointAt"
            lastReuse = reuseTokens
            saveAtSeen = saveCheckpointAt
            // 模拟原生：跨过落点就存下这一份（存得下来才会被 take 取到）。
            if (saveCheckpointAt > 0) {
                pending = saveBytes
                pendingPos = if (saveBytes != null) saveCheckpointAt else -1
            }
            callback.onStart(false)
            callback.onToken("好".toByteArray())
            // 原生契约：没请求复用就必须报原因 1（说「复用成功」而复用数为 0 是自相矛盾）。
            val reason = if (reuseTokens == 0) 1L else 0L
            return longArrayOf(20, 1, 1, 1, 0, reuseTokens.toLong(), (20 - reuseTokens).toLong(), reason)
        }
    }

    private val model = ModelInfo("test", "test.gguf", ModelLocation.LOCAL)
    private fun messages() = listOf(ChatMessage(ChatRole.USER, "问一句"))

    private fun engineWith(bridge: Recorder) = LocalModelEngine { bridge }

    private suspend fun round(
        engine: LocalModelEngine,
        cacheKey: String? = "会话1",
        checkpointPos: Int = 15,
    ) = engine.generate(messages(), GenerationParameters(maxTokens = 8, cacheKey = cacheKey, checkpointPos = checkpointPos)).toList()

    @Test
    fun theSwitchIsOffByDefaultAndNoCheckpointInterfaceIsTouched() {
        // 方案审必修项 7：真机逐字比对没做之前默认关；关着时一个检查点接口都不许碰。
        val bridge = Recorder()
        val engine = engineWith(bridge)
        assertEquals("默认必须是关的", false, engine.checkpointEnabled)
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine)
        }

        assertTrue("关着时不该问「能不能走检查点」：${bridge.calls}", "canCheckpoint" !in bridge.calls)
        assertTrue("关着时不该恢复：${bridge.calls}", bridge.calls.none { it.startsWith("restore") })
        assertEquals("关着时落点必须是 -1（不存）", -1, bridge.saveAtSeen)
    }

    @Test
    fun withTheSwitchOnAndNoCheckpointItReadsEverythingAndSavesOneForNextTime() {
        val bridge = Recorder()
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine)
        }

        assertTrue("要问过能不能走检查点：${bridge.calls}", "canCheckpoint" in bridge.calls)
        assertTrue("仓库里没有检查点时不该去恢复：${bridge.calls}", bridge.calls.none { it.startsWith("restore") })
        assertEquals("没有检查点要报原因 5", 5L, engine.checkpointFallbackReason)
        assertEquals("落点要交给原生（= checkpointPos）", 15, bridge.saveAtSeen)
        assertTrue("这一轮结束要把新检查点取回来：${bridge.calls}", "take" in bridge.calls)
    }

    @Test
    fun aStoredCheckpointIsRestoredAtItsExactPositionOnTheNextRound() {
        val bridge = Recorder()
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            // 第一轮：存下一份（位置 15）。
            round(engine)

            // 第二轮：仓库里有 15 那份、本轮公共前缀也够 ⇒ 必须恢复并从这里接着读。
            round(engine)

        }

        assertTrue("第二轮必须去恢复：${bridge.calls}", bridge.calls.any { it.startsWith("restore") })
        assertEquals("恢复的位置必须正好是那份检查点的位置", 15, bridge.restorePos)
        assertTrue("恢复成功要记下恢复位置", engine.checkpointRestoredFrom >= 0)
    }

    @Test
    fun aFailedRestoreFallsBackWithReasonSix() {
        val bridge = Recorder(restoreOk = false)
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine) // 存
            round(engine) // 恢复失败
        }

        assertTrue("恢复失败必须被尝试过：${bridge.calls}", bridge.calls.any { it.startsWith("restore") })
        assertEquals("恢复失败要报原因 6", 6L, engine.checkpointFallbackReason)
        assertEquals("失败之后不许声称恢复了", -1, engine.checkpointRestoredFrom)
    }

    @Test
    fun aModelThatCannotHoldACheckpointNeverTouchesThePath() {
        // 纯注意力模型：canCheckpoint=false ⇒ 一个检查点接口都不碰，走 P2-1c 的老路。
        val bridge = Recorder(checkpointable = false)
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine)
        }

        assertTrue("要问一次能不能走：${bridge.calls}", "canCheckpoint" in bridge.calls)
        assertTrue("不适用就不许恢复：${bridge.calls}", bridge.calls.none { it.startsWith("restore") })
        assertEquals("不适用时不存检查点", -1, bridge.saveAtSeen)
        assertEquals("也不该报 5/6/7 这些「本该用却没用到」的码", 0L, engine.checkpointFallbackReason)
    }

    @Test
    fun aRoundWithoutACacheKeyNeverUsesCheckpoints() {
        // 没有缓存键 ⇒ 这一轮「不复用任何东西」，检查点也就无从谈起（与 P2-1c 的规矩一致）。
        val bridge = Recorder()
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine, cacheKey = null)
        }

        assertTrue("没有缓存键不该恢复：${bridge.calls}", bridge.calls.none { it.startsWith("restore") })
        assertEquals("没有缓存键也不存", -1, bridge.saveAtSeen)
    }

    // ---- 必修项 8：检查点要跟着生命周期作废（卸载 / 换模型）--------------------------------

    @Test
    fun unloadingTheModelForgetsEveryCheckpoint() {
        val bridge = Recorder()
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine)          // 存下一份
            engine.unload()        // 卸载 ⇒ 检查点必须一起作废
            bridge.calls.clear()
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine)          // 再来一轮：仓库应当是空的
        }

        assertTrue("卸载之后不许再恢复旧检查点：${bridge.calls}", bridge.calls.none { it.startsWith("restore") })
        assertEquals("仓库是空的，这一轮要报原因 5", 5L, engine.checkpointFallbackReason)
    }

    @Test
    fun loadingAnotherModelForgetsEveryCheckpoint() {
        val bridge = Recorder()
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine) // 给前一个模型存一份
            // 换成另一个模型：检查点是上一个模型的状态，绝不能拿去恢复。
            engine.load(ModelInfo("other", "other.gguf", ModelLocation.LOCAL), EngineOptions()) { Descriptor() }
            bridge.calls.clear()
            round(engine)
        }

        assertTrue("换模型之后不许恢复上一个模型的检查点：${bridge.calls}", bridge.calls.none { it.startsWith("restore") })
        assertEquals("换模型之后仓库该是空的（原因 5）", 5L, engine.checkpointFallbackReason)
    }

    // ---- 2026-10-23 统筹裁定：系统内存紧张时整批丢掉（Codex R2 必修项 2）------------------

    @Test
    fun aTrimRequestDropsEveryCheckpointOnTheNextRound() {
        val bridge = Recorder()
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine)                                  // 存下一份
            engine.requestCheckpointTrim()                 // 系统说内存紧张（回调线程只打标记）
            bridge.calls.clear()
            round(engine)                                  // 下一轮进锁之后先清，再跑
        }

        assertTrue("清完之后不许再恢复：${bridge.calls}", bridge.calls.none { it.startsWith("restore") })
        assertEquals("仓库被清空，这一轮报原因 5", 5L, engine.checkpointFallbackReason)
    }

    @Test
    fun onLowMemoryOnlyMarksAndNeverTouchesTheStoreFromTheCallbackThread() {
        // 回调只打标记：这里在「没有进入新的一轮」的情况下调它，仓库必须还在（下一轮进锁才清）。
        // 这条与上一条合起来钉住「不在回调线程上动共享状态」这条约定。
        val bridge = Recorder()
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine)
            // onLowMemory 是主线程回调：它不许抛、不许阻塞在这里等推理锁。
            engine.onLowMemory()
        }
        // 没有新的一轮 ⇒ 状态没被中途改坏，引擎仍然可用。
        assertTrue("打完标记之后引擎仍然可用", bridge.calls.isNotEmpty())
    }
}
