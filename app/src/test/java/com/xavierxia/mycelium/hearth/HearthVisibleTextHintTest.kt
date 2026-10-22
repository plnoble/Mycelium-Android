package com.xavierxia.mycelium.hearth

import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows
import java.time.Duration

/**
 * P2-1c 的 D（统筹 3.1 裁定）：本地轮次超过阈值还没有**可显示正文**时，给一句提示 —— 可以停止，
 * 或者给这个人物换成云端模型。提示与加载/错误提示分开，也不改生成内容。
 *
 * 阈值做成可注入的常量（[HearthViewModel.hiddenWaitHintMillis]），时钟用 Robolectric 的主线程调度器
 * 显式往前推，所以测例既不真等 20 秒、也不靠「睡一会儿再看」。
 */
class HearthVisibleTextHintTest : HearthJvmTestCase() {

    /** 把主线程的调度时钟往前推，让 delay 里的那一轮提示真的到点。 */
    private fun advanceMainClock(millis: Long) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    private fun readyCharacter(name: String): HearthCharacter {
        val character = newCharacter(name)
        engine.preload(registeredModel)
        engine.tokens = listOf("好")
        openReady(character)
        model.hiddenWaitHintMillis = 60
        return character
    }

    @Test
    fun aRoundWithNoVisibleTextGetsTheHintAndItGoesAwayOnceTextArrives() {
        readyCharacter("等待正文提示")
        engine.holdNextGeneration()

        model.send("这一轮一个字都不出")
        waitFor(what = "这一轮真的进了生成") { engine.generates.get() == 1 }
        advanceMainClock(500)
        assertTrue("超过阈值还没有正文就该提示", model.state.value.waitingForVisibleText)

        engine.releaseGeneration()
        waitFor(what = "有正文之后提示撤掉") { !model.state.value.waitingForVisibleText }
        settle()
        assertFalse("出过正文之后不许再有提示", model.state.value.waitingForVisibleText)
    }

    @Test
    fun aRoundThatProducesTextStraightAwayNeverShowsTheHint() {
        readyCharacter("马上出字")

        model.send("正常一轮")
        waitFor(what = "这一轮生成完") { engine.generates.get() == 1 && !model.state.value.busy }
        // 越过阈值再推一次时钟：已经出过字，提示不该冒出来（撤提示那一句被改坏时这里会红）。
        advanceMainClock(500)
        assertFalse("已经出过字的一轮不该提示", model.state.value.waitingForVisibleText)
    }

    @Test
    fun stoppingClearsTheHintAndLeavingThePageToo() {
        readyCharacter("停止与换页")
        engine.holdNextGeneration()

        model.send("停掉这一轮")
        waitFor(what = "这一轮真的进了生成") { engine.generates.get() == 1 }
        advanceMainClock(500)
        assertTrue("应当先看到提示", model.state.value.waitingForVisibleText)

        model.stop()
        waitFor(what = "停止之后提示撤掉") { !model.state.value.waitingForVisibleText && !model.state.value.busy }

        // 再摆一次：换页（abandonTurn 那条路）也要撤掉提示。
        engine.holdNextGeneration()
        model.send("换页这一轮")
        waitFor(what = "这一轮真的进了生成") { engine.generates.get() == 2 }
        advanceMainClock(500)
        assertTrue("应当再次看到提示", model.state.value.waitingForVisibleText)

        model.newConversation()
        waitFor(what = "换页之后提示撤掉") { !model.state.value.waitingForVisibleText }
    }
}