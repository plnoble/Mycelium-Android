package com.xavierxia.mycelium.hearth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复审 r3 第 4 条：停止信号必须活到生成启动边界。
 *
 * 原来只有「这一轮要加载模型」那条路上问了一次停止意图，而且问完之后到真正 `generate()` 之间还有
 * 一段没人管的窗口；已经加载好的模型更是完全跳过那次检查。于是加载返回后、或进 IO 之前按下的停止
 * 会被丢掉：照常开始生成，迟到的 token 又把 `produced` 置真，最后允许保存。
 *
 * 现在这一轮的停止状态由轮次自己带着，在「进 IO 之前」与「真正问模型之前」各问一次，写进缓冲的
 * token 也只收停止之前的那部分。
 */
class HearthStopBeforeGenerateTest : HearthJvmTestCase() {

    @Test
    fun aStopThatLandsBeforeAnAlreadyLoadedModelGeneratesKeepsTheTurnFromProducingAnything() {
        val character = newCharacter("已加载模型停止复验")
        engine.preload(registeredModel)
        openReady(character)
        engine.tokens = listOf("不该出现的回答")

        model.send("停止复验：这句不该有回答")
        // activeTurn 在 send() 里就记好了，所以这一下必然落在这一轮上。
        model.stop()
        settle(1_000)

        assertEquals("已经加载好的模型也必须受这一轮停止状态约束", 0, engine.generates.get())
        assertEquals("停止之后问题应该回到输入框", "停止复验：这句不该有回答", model.state.value.pendingQuestion)
        assertTrue("停在没有输出之前，一个字都不该入库", log.conversations(character.id).isEmpty())
        assertTrue("不该有回答上屏", model.state.value.messages.none { it.text.contains("不该出现的回答") })
        assertFalse("停止之后不该还显示生成中", model.state.value.busy)
    }

    @Test
    fun aStopThatLandsWhileTheWindowIsStillBeingReadKeepsTheTurnFromProducingAnything() {
        val character = newCharacter("读窗口停止复验")
        val conversation = log.append(
            character.id, null, "读窗口复验：原来的问题", "读窗口复验：原来的回答", "本机 · 测试", minutesAgo(2),
        )
        engine.preload(registeredModel)
        engine.tokens = listOf("不该出现的回答")
        openReady(character)
        assertEquals(conversation, model.state.value.openConversation)

        // 让这一轮的 prompt 窗口正好读在历史那一条 SQL 上：检查通过之后、真正生成之前的窗口。
        val held = gates.holdRead("SELECT role, text, model FROM messages")
        model.send("读窗口复验：这句不该有回答")
        waitFor(what = "窗口的历史读取被停住") { held.reached() }
        model.stop()
        held.letGo()
        settle(1_000)

        assertEquals("窗口读完之后的停止同样不许开始生成", 0, engine.generates.get())
        assertEquals("被停住的这一轮不该往库里写东西", 2, log.messages(conversation).size)
        assertEquals("问题应该回到输入框", "读窗口复验：这句不该有回答", model.state.value.pendingQuestion)
        assertFalse("停止之后不该还显示生成中", model.state.value.busy)
    }

    @Test
    fun aStopWhileTheModelIsLoadingStoresNothingEvenWhenTheLoadComesBack() {
        val character = newCharacter("加载期停止复验")
        openReady(character)
        // 闸门要在页面打开之后再架：打开人物时 abandonTurn() 也会调一次 engine.stop()，
        // 那道停止信号会顺手把还没用上的闸门放行。
        engine.holdNextLoad()
        engine.tokens = listOf("不该出现的回答")

        model.send("加载期复验：这句不该有回答")
        waitFor(what = "模型开始加载") { engine.loadStarted.count == 0L }
        model.stop()
        settle(1_000)

        assertTrue("加载确实成功了（native 最坏的样子）", engine.loadedModel.value != null)
        assertEquals("加载成功也不许接着生成", 0, engine.generates.get())
        assertEquals("停止之后问题应该回到输入框", "加载期复验：这句不该有回答", model.state.value.pendingQuestion)
        assertTrue("这一轮一个字都不该入库", log.conversations(character.id).isEmpty())
    }

    /**
     * 复审 r4 第 3 条：本轮停止必须**真的终止**这一轮本地生成任务。
     *
     * 原来本地 `stop()` 只设引擎标记：真引擎在本轮开头就会把它清掉（`runNative()` 清 Kotlin 的，
     * `native.begin()` 清 native 的），`return@collect` 也只是不显示迟到的 token，并不终止生成，
     * 页面于是要一直忙到推理自然结束。
     *
     * 这里把这一轮停在引擎里面（模型已加载、两次检查都过了、已经进入这一轮生成，但一个 token 都还
     * 没吐），就在这一点按停止：任务必须被拆掉（[FakeLocalEngine.cancelled]），页面必须马上退出
     * 忙碌 —— 假生成一直挂在原地，如果只是设了标记，它会在等待里超时，而不是变绿。
     */
    @Test
    fun aStopAfterTheChecksPassTearsDownTheLocalGenerationInsteadOfWaitingForItToFinish() {
        val character = newCharacter("生成中停止复验")
        engine.preload(registeredModel)
        openReady(character)
        engine.tokens = listOf("不该出现的回答")
        engine.holdNextGeneration()

        model.send("生成中停止复验：这句不该有回答")
        waitFor(what = "这一轮已经交给引擎（两次检查都过了）") { engine.generates.get() == 1 }

        model.stop()
        waitFor(what = "停止之后页面立刻退出忙碌") { !model.state.value.busy }

        assertEquals("本轮停止必须真的拆掉这一轮的任务，而不只是设标记", 1, engine.cancelled.get())
        assertEquals("这一轮不该自己走完", 0, engine.completed.get())
        assertTrue("停止之后不该有 token 上屏", model.state.value.messages.none { it.text.contains("不该出现的回答") })
        assertTrue("一个 token 都没有就停止，一个字都不该入库", log.conversations(character.id).isEmpty())
        assertEquals("停止之后问题应该回到输入框", "生成中停止复验：这句不该有回答", model.state.value.pendingQuestion)
    }
}
