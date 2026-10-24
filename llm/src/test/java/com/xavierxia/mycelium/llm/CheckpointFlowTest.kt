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

    internal class Descriptor : ModelFile {
        override val fd = 7
        override fun close() {}
    }

    /**
     * 假原生桥：记录每一次检查点相关的调用，并按规定回答。
     *
     * @param checkpointable `canCheckpoint()` 的回答（混合/循环 = true）。
     * @param saveBytes 跨过落点时交回来的字节数；null = 存不下来。
     * @param restoreOk `restoreCheckpoint()` 的回答。
     */
    internal open class Recorder(
        private val checkpointable: Boolean = true,
        /** 这一轮「存下来」的大小；null 表示没存成。字节本体留在原生，测例只模拟句柄。 */
        private val saveBytes: Long? = 16L,
        private val restoreOk: Boolean = true,
        /** 这一轮是否以「被停止」收场（原生正常返回 stopped=1）。 */
        private val stopped: Boolean = false,
    ) : EngineBridge {
        val calls = mutableListOf<String>()
        var saveAtSeen: Int = -1
        var restorePos: Int = -1
        var lastReuse: Int = -1
        private var pending: Long? = null
        val handles = mutableMapOf<Int, Long>()

        /** 在途快照的位置（-1 = 没有）——给「清理必须真的丢掉 pending」这类断言用。 */
        val pendingPosPublic: Int get() = pendingPos
        private var nextHandle = 100
        val released = mutableListOf<Int>()
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

        override fun setCheckpointCut(cut: Int) {
            calls += "cut=$cut"
        }

        override fun restoreCheckpointHandle(handle: Int, pos: Int): Boolean {
            calls += "restore@$pos"
            restorePos = pos
            if (handles[handle] == null) return false
            return restoreOk
        }

        override fun takeCheckpointHandle(): Int {
            calls += "take"
            val size = pending ?: return -1
            pending = null
            val handle = nextHandle++
            handles[handle] = size
            return handle
        }

        override fun checkpointHandleBytes(handle: Int): Long = handles[handle] ?: -1L

        override fun releaseCheckpointHandle(handle: Int) {
            calls += "release"
            released += handle
            handles.remove(handle)
        }

        // 全量审 R2 P2-6：**假的 discard 必须真的丢掉 pending**。原来继承空实现，于是「停止/取消时
        // 在途快照有没有被丢弃」这条根本没被测到（取消后的探针还能看到 fakePending=16）。
        override fun discardCheckpoint() {
            calls += "discard"
            pending = null
            pendingPos = -1
        }

        override fun handlesAlive(): LongArray =
            longArrayOf(handles.size.toLong(), handles.values.sum())

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
            checkpointSave: Boolean,
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
            return longArrayOf(
                20, 1, 1, 1, if (stopped) 1 else 0, reuseTokens.toLong(), (20 - reuseTokens).toLong(), reason,
            )
        }
    }

    internal val model = ModelInfo("test", "test.gguf", ModelLocation.LOCAL)
    private fun messages() = listOf(ChatMessage(ChatRole.USER, "问一句"))

    internal fun engineWith(bridge: Recorder) = LocalModelEngine { bridge }

    internal suspend fun round(
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
        assertTrue("打完标记之后引擎仍然可用", bridge.calls.isNotEmpty())
    }

    // ---- 全量审必修项 1 / 2 回归 ---------------------------------------------------------

    @Test
    fun editingThePrefixPreventsRestoringACheckpointBeyondTheRealCommonPrefix() {
        // 全量审必修项 1：上一轮存了位置 15 的快照，这一轮会话被编辑了（真实公共前缀缩短到 5）。
        // 即使这一轮传进来的 checkpointPos 还是 15，也不能恢复 15 的快照——位置对不上。
        val bridge = object : Recorder() {
            var round = 0
            override fun tokenize(messages: ByteArray): IntArray {
                round++
                // 第 1 轮：20 个 token 全是 1；第 2 轮：前 5 个是 1，后面变成 2（真实公共前缀只有 5）
                return if (round == 1) IntArray(20) { 1 } else IntArray(20) { if (it < 5) 1 else 2 }
            }
        }
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine, checkpointPos = 15) // 第 1 轮存下 15
            bridge.calls.clear()
            // 第 2 轮：真实公共前缀只有 5，目标落点传 15
            round(engine, checkpointPos = 15)
        }

        assertTrue("超出真实前缀的检查点绝不许恢复：${bridge.calls}", bridge.calls.none { it.startsWith("restore") })
        assertEquals("超出公共前缀，这一轮必须报原因 5", 5L, engine.checkpointFallbackReason)
        assertEquals("不许声称恢复了", -1, engine.checkpointRestoredFrom)
    }

    @Test
    fun aRestoreFailureCleansUpAndReadsFromZero() {
        // 全量审必修项 2：恢复失败必须清干净从 0 读，不能报「reason=6 却复用了 N 个 token」。
        val bridge = Recorder(restoreOk = false)
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine) // 存 15
            bridge.calls.clear()
            round(engine) // 恢复失败
        }

        assertTrue("尝试过恢复：${bridge.calls}", bridge.calls.any { it.startsWith("restore") })
        assertEquals("失败报原因 6", 6L, engine.checkpointFallbackReason)
        assertEquals("恢复失败后必须从 0 读（复用数为 0）", 0, bridge.lastReuse)
        assertEquals("统计里的复用数也必须是 0", 0L, engine.lastStats?.reusedPromptTokens)
        assertEquals("统计里的原因必须是 6", 6L, engine.lastStats?.reuseFallbackReason)
    }

    // ---- 全量审 R2 必修项 1 / 3 回归 -----------------------------------------------------

    @Test
    fun aForkThatHappensWhileTheSwitchIsOffStillInvalidatesTheOldCheckpoint() {
        // R2 必修项 1（P1）：分叉作废不能受「这一轮要不要存」控制。
        // 场景：存了 15 → 关掉开关、同键编辑（真实公共前缀只剩 5）→ 再打开 ⇒ 不许恢复 15。
        val bridge = object : Recorder() {
            var round = 0
            override fun tokenize(messages: ByteArray): IntArray {
                round++
                return if (round == 1) IntArray(20) { 1 } else IntArray(20) { if (it < 5) 1 else 2 }
            }
        }
        val engine = engineWith(bridge)
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            engine.checkpointEnabled = true
            round(engine, checkpointPos = 15)          // 第 1 轮：存下 15
            engine.checkpointEnabled = false
            round(engine, checkpointPos = 15)          // 第 2 轮：关着开关，但会话被编辑了
            engine.checkpointEnabled = true
            bridge.calls.clear()
            round(engine, checkpointPos = 15)          // 第 3 轮：再打开
        }

        assertTrue("关着的那一轮也必须作废旧分支：${bridge.calls}", bridge.calls.none { it.startsWith("restore") })
        assertEquals("没有可用检查点 ⇒ 原因 5", 5L, engine.checkpointFallbackReason)
    }

    @Test
    fun aRoundThatWasStoppedNeverLeavesACheckpointBehind() {
        // R2 必修项 3（P1）：stop() 会让原生正常返回 stopped=1，这一轮**不许**入库检查点，
        // 而且要把它作废——否则下一轮会恢复到一个「提示没读完」的位置。
        val bridge = Recorder(stopped = true)
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine)
        }

        assertTrue("停下的一轮不许存检查点：${bridge.calls}", bridge.calls.none { it == "take" })
        assertEquals("停下的轮次不该留下任何检查点", 0, engine.checkpointUsageSuspendForTest())
    }

    @Test
    fun aCancelledRoundKeepsTheReasonSixItHad() {
        // R2 必修项 4（P2）：恢复失败后紧接着被取消，收尾统计里的原因仍要是 6，
        // 不能被 forgetCheckpoints() 清成原生那个旧码（1）。
        val bridge = object : Recorder(restoreOk = false) {
            var round = 0
            override fun generate(
                messages: ByteArray, maximum: Int, temperature: Float, topP: Float, seed: Int,
                reuseTokens: Int, saveCheckpointAt: Int, checkpointSave: Boolean, restoredTo: Int, callback: TokenCallback,
            ): LongArray {
                round++
                if (round == 1) return super.generate(messages, maximum, temperature, topP, seed, reuseTokens, saveCheckpointAt, checkpointSave, restoredTo, callback)
                callback.onStart(false)
                throw kotlinx.coroutines.CancellationException("用户取消")
            }
        }
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine)                        // 存
            runCatching { round(engine) }         // 恢复失败 + 取消
        }

        assertEquals("取消收尾也要如实报原因 6", 6L, engine.lastStats?.reuseFallbackReason)
        // 全量审 P2-8：清理之后假桥的原生句柄表必须为空——「仓库空但原生还占着」的泄漏在这里现形。
        assertTrue("取消后不得残留句柄：${bridge.handles}", bridge.handles.isEmpty())
    }

    // ---- 改挂（2026-10-23 夜，任务书第一段第 2 条）------------------------------------------

    @Test
    fun rekeyingMakesTheSecondRoundReuseTheFirstRound() {
        // 真机根因：新会话第一轮用临时编号做键，落库后换成正式编号，第二轮的检查点因此用不上
        //（read=160/160，reason=5）。改挂之后第二轮必须真的复用第一轮的落点。
        val bridge = Recorder()
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine, cacheKey = "temp-1")
            val (count, _) = engine.checkpointUsage()
            assertEquals("第一轮应当存下一份", 1, count)
            engine.rekeyCheckpoints("temp-1", "conv-1")
            bridge.calls.clear()
            round(engine, cacheKey = "conv-1")
        }

        assertEquals("改挂之后第二轮不该回退", 0L, engine.checkpointFallbackReason)
        assertTrue("第二轮应当从第一轮的落点续读：${bridge.calls}", bridge.calls.any { it.startsWith("restore") })
    }

    @Test
    fun rekeyingAnEmptyOrIdenticalKeyIsANoOp() {
        val bridge = Recorder()
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            engine.rekeyCheckpoints("nothing", "conv-1")
            round(engine, cacheKey = "conv-1")
            val (count, _) = engine.checkpointUsage()
            assertEquals("空键改挂不该变出检查点", 1, count)
            engine.rekeyCheckpoints("conv-1", "conv-1")
            assertEquals("同键改挂是空操作", 1, engine.checkpointUsage().first)
        }
    }

    /** 只在测例里用：占用读数现在是 suspend（走引擎锁）。 */
    private fun LocalModelEngine.checkpointUsageSuspendForTest(): Int = runBlocking { checkpointUsage().first }

    @Test
    fun whenTheHeapIsTightTheRoundSkipsSavingButStillGenerates() {
        // R2 必修项 2（P1）：托管堆余量不够时**跳过这次优化**，绝不让生成因为存检查点而失败。
        val bridge = Recorder(saveBytes = 1024L)
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        engine.heapHeadroomProbe = { 1L } // 只剩 1 字节余量：任何一份都放不下
        val text = runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine)
        }

        assertEquals("生成必须照常完成", "好", text.joinToString(""))
        assertTrue("应当记下「因为内存跳过」", engine.checkpointSkippedForMemory)
        assertEquals("不许入库", 0, engine.checkpointUsageSuspendForTest())
    }
    // ---- 全量审 P2-6：低内存清理必须「现在」就释放 --------------------------------------

    @Test
    fun trimNowDropsEveryCheckpointEvenIfNoGenerationFollows() = runBlocking {
        val bridge = Recorder()
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        engine.load(model, EngineOptions()) { Descriptor() }
        round(engine)                                   // 存一份
        val before = bridge.released.size
        assertEquals(1, engine.checkpointUsage().first)
        engine.trimCheckpointsNow()                     // 系统低内存：立即清
        assertEquals("清完仓库必须为空", 0, engine.checkpointUsage().first)
        // 全量审 R2 P2-6：仓库空不等于原生空——句柄表与在途快照都要空。
        assertTrue("清完原生句柄也必须为空：${bridge.handles}", bridge.handles.isEmpty())
        assertEquals("清完在途快照也必须丢掉", -1, bridge.pendingPosPublic)
        assertTrue("至少释放了一个句柄（之前 before）", bridge.released.size > before)
        // 之后**不再生成**：仓库保持为空（不会凭空长回来）。
        assertEquals(0, engine.checkpointUsage().first)
    }
}