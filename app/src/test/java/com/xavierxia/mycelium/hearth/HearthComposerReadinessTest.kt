package com.xavierxia.mycelium.hearth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复审 r4 第 2 条（本轮新回归）：历史失败态没有接进页面。
 *
 * 原来页面自己按 `historyLoading` 猜能不能发，而失败态是 `historyLoading=false, historyFailed=true`:
 * 发送按钮可点，`send()` 却因失败态直接返回；页面又是**先**清空输入框再发送，于是「按钮能点、没有
 * 请求、输入的这句话消失」，而且页面上没有 `retryHistory()` 的入口。
 *
 * 现在页面与 ViewModel 用同一个判据（[HearthState.canSend] / [HearthState.sendBlockedReason]，
 * 页面渲染的是 [composerRow]），失败态有明确的重试入口，草稿只在 [HearthViewModel.send] 返回 true
 * （真的被接受）之后才清空。
 */
class HearthComposerReadinessTest : HearthJvmTestCase() {

    /**
     * ①读取失败：发送被拒、**草稿留在输入框里**、页面显示重试入口。
     */
    @Test
    fun aFailedHistoryReadRefusesTheSendKeepsTheDraftAndShowsTheRetryEntrance() {
        val left = newCharacter("输入框复验：上一个")
        val leftConversation = log.append(left.id, null, "上一个的问题", "上一个的回答", "本机 · 测试", minutesAgo(2))
        val opened = newCharacter("输入框复验：新开的")
        openReady(left)
        assertEquals("前提：打开的会话属于上一个人物", leftConversation, model.state.value.openConversation)

        gates.failNextRead("SELECT id, character_id, title, updated_at", "历史读不出来（用例注入）")
        model.openChat(opened)
        waitFor(what = "失败的历史读取落定") { !model.state.value.historyLoading }
        assertTrue("前提：这一页处于历史读取失败态", model.state.value.historyFailed)

        // 页面这一行现在长这样：发送按钮不可点，按钮下面写着原因，旁边有重试入口。
        var box = "读取失败后这句不该发出去"
        val row = composerRow(model.state.value, box)
        assertFalse("失败态下发送按钮不该可点", row.canSend)
        assertTrue("失败态下页面必须有重试入口", row.retryVisible)
        assertNotNull("不能发的时候页面要说得出原因", row.blockedReason)
        assertEquals(
            "页面与 ViewModel 必须是同一个判据：草稿非空时按钮的可用性就是 canSend",
            model.state.value.canSend,
            composerRow(model.state.value, "非空草稿").canSend,
        )

        // 点「发送」这一下：ViewModel 拒收，草稿原样留在框里。
        sendFromComposer(model, box) { box = "" }
        settle()

        assertEquals("发送被拒时草稿必须留在输入框里", "读取失败后这句不该发出去", box)
        assertEquals("发送被拒时不该开始生成", 0, engine.generates.get())
        assertTrue(
            "被拒的句子绝不许落到上一个人的会话里",
            log.messages(leftConversation).none { it.text.contains("读取失败后这句不该发出去") },
        )
        assertTrue("被拒的句子不该上屏", model.state.value.messages.none { it.text.contains("读取失败后这句不该发出去") })
    }

    /**
     * ②重试成功之后，同一个草稿可以正常发出去，并且**只在被接受之后**才从输入框消失。
     */
    @Test
    fun retryingTheFailedReadLetsTheSameDraftGoThroughAndOnlyThenClearsIt() {
        val left = newCharacter("重试输入复验：上一个")
        openReady(left)
        val opened = newCharacter("重试输入复验：新开的")

        gates.failNextRead("SELECT id, character_id, title, updated_at", "历史读不出来（用例注入）")
        model.openChat(opened)
        waitFor(what = "失败的历史读取落定") { !model.state.value.historyLoading }

        var box = "重试成功之后这句该发出去"
        sendFromComposer(model, box) { box = "" }
        settle()
        assertEquals("失败态下草稿不该被清掉", "重试成功之后这句该发出去", box)
        assertTrue("失败态下页面仍应显示重试入口", composerRow(model.state.value, box).retryVisible)

        // 页面上的重试入口：读成功之后这一页重新可用。
        model.retryHistory()
        waitFor(what = "重试的历史读取完成") { !model.state.value.historyLoading && !model.state.value.historyFailed }
        val row = composerRow(model.state.value, box)
        assertTrue("重试成功之后发送按钮应该可点", row.canSend)
        assertFalse("重试成功之后不该再显示重试入口", row.retryVisible)
        assertNull("能发的时候页面不该再写原因", row.blockedReason)

        // 这一次点击被接受，草稿才清空，并且真的生成、入库。
        sendFromComposer(model, box) { box = "" }
        assertEquals("被接受的发送才清空草稿", "", box)
        waitFor(what = "重试之后的这一轮生成完") { !model.state.value.busy && log.conversations(opened.id).isNotEmpty() }

        val stored = log.conversations(opened.id).single().id
        assertTrue("重试之后的问题应该进这个人物的会话", log.messages(stored).any { it.text.contains("重试成功之后这句该发出去") })
        assertEquals("重试之后应该真的生成了一轮", 1, engine.generates.get())
    }

    /**
     * ③切回沉浸模式、历史还没读完时，发送必须被拒 —— 测试模式本身是合法的可用状态（它不读写库），
     * 但「模式切换中」不能被当成测试模式的就绪。
     */
    @Test
    fun aSendWhileThePageIsSwitchingBackToImmersiveIsRefusedUntilItsHistoryIsThere() {
        val character = newCharacter("切模式复验")
        openReady(character)

        model.setMode(HearthMode.TEST)
        waitFor(what = "切到测试模式") { model.state.value.mode == HearthMode.TEST }
        // 测试模式自身的合法路径：不读不写库，进入它就是可用状态。
        assertTrue("测试模式没有库要等，应该直接可以发", model.state.value.canSend)

        // 切回沉浸模式，并把这次历史读取停在半路。
        val held = gates.holdRead("SELECT id, character_id, title, updated_at")
        model.setMode(HearthMode.IMMERSIVE)
        waitFor(what = "切回沉浸模式的历史读取被停住") { held.reached() }

        assertTrue("读取期间页面必须处于历史未就绪状态", model.state.value.historyLoading)
        assertFalse("切到沉浸模式但历史没读完时不能发", model.state.value.canSend)

        var box = "切回沉浸模式这句不该发出去"
        sendFromComposer(model, box) { box = "" }
        settle()
        // 这几条是这个窗口的判别点：守住了就一次都不该开始生成，被拒的草稿必须留下；而模式必须
        // 在读取开始时就切过去（旧写法把它留到读完之后，这一窗口因此被当成了「测试模式」）。
        assertEquals("模式切换期间不该开始生成", 0, engine.generates.get())
        assertEquals("被拒的发送必须把草稿留在框里", "切回沉浸模式这句不该发出去", box)
        assertTrue("模式切换期间被拒的句子不该入库", log.conversations(character.id).isEmpty())
        assertEquals("模式必须在读取开始时就切过去", HearthMode.IMMERSIVE, model.state.value.mode)

        held.letGo()
        waitFor(what = "切回沉浸模式的历史读完") { !model.state.value.historyLoading }
        assertTrue("历史读完（并且没有失败）之后才可以发", model.state.value.canSend && !model.state.value.historyFailed)
    }
}
