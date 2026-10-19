package com.xavierxia.mycelium.hearth

import com.xavierxia.mycelium.model.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复审 r3 第 3 条：重做的写库被拒之后，不许再把旧回答替换到新页面上。
 *
 * 原来 [HearthViewModel] 的 `replaceTurn()` 把所有 `stored == null` 都当成「测试模式，只改屏幕」。
 * 于是一轮本地重做带着半截内容走到 `NonCancellable` 收尾、写事务又正确拒写时，返回后照样把这段
 * 旧回答盖到用户已经翻过去的那一页上。现在测试模式与该轮作废被分开判断，并且更新屏幕之前复检令牌。
 *
 * 时序用 [Gate] 停在写事务的 `BEGIN IMMEDIATE` **之前**（真机夹具也是停在这里），所以停住期间不占
 * 写锁；测试在这一刻让页面被新的一次历史读取接管（`beginGeneration`：换轮次，不取消正在写的这一
 * 轮）——这正是「写事务被拒、协程却没被取消」那一态，也就是原缺陷唯一能落到屏幕上的路径。真正的
 * 导航会顺手取消任务，而取消会让 `withContext` 直接抛 CancellationException。
 */
class HearthRedoWriteRefusedTest : HearthJvmTestCase() {

    @Test
    fun aRefusedRedoWriteNeverReplacesTheAnswerOnThePageThatMovedOn() {
        val character = newCharacter("重做复验人物")
        val conversation = log.append(
            character.id, null, "重做复验：原来的问题", "重做复验：原来的回答", "本机 · 测试", minutesAgo(2),
        )
        openReady(character)
        assertEquals("打开人物时应该落在它自己的那段对话上", conversation, model.state.value.openConversation)

        engine.tokens = listOf("重做复验：这一轮的半截回答")
        val held = gates.holdTransaction(2)
        model.regenerate()
        waitFor(what = "重做这一轮走进写库事务") { held.reached() }

        // 写事务还在等：页面被一次新的历史读取接管（同一段对话，但轮次换了）。
        model.retryHistory()
        waitFor(what = "重新读上来的历史就位") { !model.state.value.historyLoading }
        assertEquals("重读之后页面还在原来那段对话上", conversation, model.state.value.openConversation)
        assertTrue(
            "重读之后页面显示的是库里的原回答",
            model.state.value.messages.any { it.text == "重做复验：原来的回答" },
        )

        held.letGo()
        settle(1_000)

        val shown = model.state.value.messages.filter { it.role == ChatRole.ASSISTANT }.map { it.text }
        assertEquals(
            "写库被拒之后不许把这一轮的半截回答替换到新页面上",
            listOf("重做复验：原来的回答"),
            shown,
        )
        assertEquals("库里也不许被这一轮改写", "重做复验：原来的回答", log.messages(conversation).last().text)
        assertFalse("写库被拒之后不该还显示生成中", model.state.value.busy)
    }
}
