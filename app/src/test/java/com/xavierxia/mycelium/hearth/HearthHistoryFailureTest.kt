package com.xavierxia.mycelium.hearth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复审 r3 第 2 条：历史读取失败不是「历史已就绪」。
 *
 * 原来切人物时保留着上一页的 `openConversation`，而读取失败只清掉 `historyLoading`。于是「关掉人物
 * A、打开 B、B 的历史读失败、随后发送」会捕获 **B 人物 ＋ A 会话**：提问被读进 A 的上下文、回答被
 * 追加进 A 的会话，`window()` / `append()` 两侧都没有会话归属校验。
 *
 * 现在：切人物开始时就把旧目标清掉，读取失败进入明确的失败状态（继续禁止发送，`retryHistory()`
 * 重读），并且在窗口边界与写边界各问一次「这段会话属于这个人物吗」。
 */
class HearthHistoryFailureTest : HearthJvmTestCase() {

    @Test
    fun aSendAfterAFailedHistoryReadIsRefusedInsteadOfGoingToTheConversationThePageLeft() {
        val left = newCharacter("失败复验：上一个")
        val leftConversation = log.append(left.id, null, "上一个的问题", "上一个的回答", "本机 · 测试", minutesAgo(2))
        val opened = newCharacter("失败复验：新开的")

        openReady(left)
        assertEquals("打开人物时应该落在它自己的会话上", leftConversation, model.state.value.openConversation)

        // 切到另一个人物，并让这次历史读取失败。
        gates.failNextRead("SELECT id, character_id, title, updated_at", "历史读不出来（用例注入）")
        model.openChat(opened)
        waitFor(what = "失败的历史读取落定") { !model.state.value.historyLoading }

        assertTrue("读取失败必须进入失败状态", model.state.value.historyFailed)
        assertNull("读取失败之后不该还留着上一个会话当目标", model.state.value.openConversation)

        // 就在这个状态里发送：一次都不该出去，也不许落到上一个人的会话里。
        model.send("读取失败后这句不该发出去")
        settle()

        assertEquals("历史读取失败时不该开始生成", 0, engine.generates.get())
        assertTrue(
            "读取失败后的提问绝不许写进上一个人的会话",
            log.messages(leftConversation).none { it.text.contains("读取失败后这句不该发出去") },
        )
        assertTrue("读取失败之后这个人物不该被建出会话", log.conversations(opened.id).isEmpty())
        assertTrue("被拒的提问不该上屏", model.state.value.messages.none { it.text.contains("读取失败后这句不该发出去") })
    }

    @Test
    fun retryingTheFailedReadLetsTheNextSendGoThrough() {
        val left = newCharacter("重试复验：上一个")
        val leftConversation = log.append(left.id, null, "上一个的问题", "上一个的回答", "本机 · 测试", minutesAgo(2))
        val opened = newCharacter("重试复验：新开的")
        openReady(left)

        gates.failNextRead("SELECT id, character_id, title, updated_at", "历史读不出来（用例注入）")
        model.openChat(opened)
        waitFor(what = "失败的历史读取落定") { !model.state.value.historyLoading }
        model.send("重试之前的这句不该发出去")
        settle()
        assertEquals("失败状态下不该生成", 0, engine.generates.get())
        assertTrue("失败状态下被拒的提问不该进上一个人的会话", log.messages(leftConversation).none { it.text.contains("重试之前的这句") })

        // 重读成功之后，页面才算就绪，同一句话应该正常进入这个人物自己的会话。
        model.retryHistory()
        waitFor(what = "重试的历史读取完成") { !model.state.value.historyLoading && !model.state.value.historyFailed }

        model.send("重试之后这句应该发出去")
        waitFor(what = "重试之后这一轮生成完") { !model.state.value.busy && log.conversations(opened.id).isNotEmpty() }

        val stored = log.conversations(opened.id).single().id
        assertTrue("重试之后的问题应该进这个人物的会话", log.messages(stored).any { it.text.contains("重试之后这句应该发出去") })
        assertTrue("重试之后绝不能写进上一个人的会话", log.messages(leftConversation).none { it.text.contains("重试之后这句应该发出去") })
        assertEquals("重试之后应该真的生成了一轮", 1, engine.generates.get())
    }

    @Test
    fun aConversationThatDoesNotBelongToTheOpenCharacterIsRefusedAndThePageHeals() {
        val owner = newCharacter("归属复验：会话主人")
        val foreign = log.append(owner.id, null, "主人的问题", "主人的回答", "本机 · 测试", minutesAgo(2))
        val opened = newCharacter("归属复验：页面人物")

        openReady(opened)
        // 页面被指到另一个人的会话上：这正是失败读取后旧写法会留下的那一态。
        model.openConversation(foreign)
        waitFor(what = "别人的会话读上来") { !model.state.value.historyLoading }
        assertEquals("用例摆出的前提：页面目标属于另一个人", foreign, model.state.value.openConversation)

        model.send("别人的会话里这句不该发出去")
        waitFor(what = "页面自愈：目标被清掉并重读自己的历史") {
            model.state.value.openConversation != foreign && !model.state.value.historyLoading
        }
        settle()

        assertEquals("会话不属于这个人物时不该开始生成", 0, engine.generates.get())
        assertTrue(
            "别人的会话里一个字都不许被追加",
            log.messages(foreign).none { it.text.contains("别人的会话里这句不该发出去") },
        )
        assertFalse("自愈之后不该还留着别人的会话当目标", model.state.value.openConversation == foreign)

        // 自愈之后，这个人物的页面上一切照旧能用。
        model.send("自愈之后这句该进自己的会话")
        waitFor(what = "自愈之后这一轮生成完") { !model.state.value.busy && log.conversations(opened.id).isNotEmpty() }
        val stored = log.conversations(opened.id).single().id
        assertTrue("自愈之后的问题应该进自己的会话", log.messages(stored).any { it.text.contains("自愈之后这句该进自己的会话") })
        assertTrue("自愈之后别人的会话仍然只有一个字都不多", log.messages(foreign).size == 2)
    }
}
