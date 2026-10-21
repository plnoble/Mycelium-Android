package com.xavierxia.mycelium.hearth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-1c 第 3 步：炉边必须给出缓存键，而且键要跟着「哪个人物的哪段会话、哪个模型」走。
 *
 * 假引擎记下每一轮真正收到的键，所以这里断言的是**实际调用**，不是重新算一遍公式；键的比较用的是
 * 同一个纯函数（`localPromptCacheKey`），公式本身的稳定性与区分度由 `:hearth` 的 PromptTest 覆盖。
 */
class HearthPromptCacheKeyTest : HearthJvmTestCase() {

    private fun readyCharacter(name: String): HearthCharacter {
        val character = newCharacter(name)
        engine.preload(registeredModel)
        engine.tokens = listOf("好")
        openReady(character)
        return character
    }

    @Test
    fun aLocalTurnCarriesTheKeyOfTheConversationAndModelItBelongsTo() {
        val character = readyCharacter("缓存键复验")

        model.send("第一问")
        waitFor(what = "第一轮生成完并记下键") { engine.cacheKeys.size == 1 && !model.state.value.busy }

        val key = engine.cacheKeys.single()
        assertNotNull("本地轮次必须带缓存键", key)
        val conversationId = model.state.value.openConversation
        assertNotNull("第一轮落库之后页面应该有了会话 ID", conversationId)
        assertTrue("键要带人物 ID 与模式", key!!.startsWith("${character.id}|${HearthMode.IMMERSIVE.name}|"))
        assertTrue("键要带这一轮实际用的模型 ID", key.endsWith("|${registeredModel.sha256}"))
        assertNotEquals(
            "新建会话的第一轮还没有会话 ID，用的是只在内存里的临时身份",
            localPromptCacheKey(character, HearthMode.IMMERSIVE, conversationId, "conv", registeredModel.sha256),
            key,
        )

        // 会话落库之后，下一轮的键换成真实会话 ID：这是统筹批准的已知小损失（第二轮仍整段重读）。
        model.send("第二问")
        waitFor(what = "第二轮生成完") { engine.cacheKeys.size == 2 && !model.state.value.busy }
        assertEquals(
            "落库之后这一轮的键就是真实会话 ID 那个",
            localPromptCacheKey(character, HearthMode.IMMERSIVE, model.state.value.openConversation, "ignored", registeredModel.sha256),
            engine.cacheKeys[1],
        )
    }

    @Test
    fun twoNewConversationsOfTheSameCharacterNeverShareAKey() {
        readyCharacter("两段新会话")

        model.send("第一段的第一问")
        waitFor(what = "第一段生成完") { engine.cacheKeys.size == 1 && !model.state.value.busy }
        val first = engine.cacheKeys.single()

        // 新会话：还没有落库，所以这时候的键只能靠那个只在内存里的临时身份区分。
        model.newConversation()
        waitFor(what = "页面回到新会话") { model.state.value.openConversation == null }
        model.send("第二段的第一问")
        waitFor(what = "第二段生成完") { engine.cacheKeys.size == 2 && !model.state.value.busy }
        val second = engine.cacheKeys[1]

        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals("同一个人物连开两段新会话，不能共用同一个键", first, second)
    }

    @Test
    fun leavingAndReenteringTestModeDrawsANewKeyEachTime() {
        readyCharacter("测试模式换键")

        model.send("沉浸模式的一问")
        waitFor(what = "沉浸模式这一轮生成完") { engine.cacheKeys.size == 1 && !model.state.value.busy }
        val immersive = engine.cacheKeys[0]

        model.setMode(HearthMode.TEST)
        waitFor(what = "进入测试模式") { model.state.value.mode == HearthMode.TEST }
        model.send("测试模式的第一问")
        waitFor(what = "测试模式这一轮生成完") { engine.cacheKeys.size == 2 && !model.state.value.busy }
        val firstTestTurn = engine.cacheKeys[1]

        model.setMode(HearthMode.IMMERSIVE)
        waitFor(what = "退回沉浸模式") { model.state.value.mode == HearthMode.IMMERSIVE }
        model.setMode(HearthMode.TEST)
        waitFor(what = "再次进入测试模式") { model.state.value.mode == HearthMode.TEST }
        model.send("测试模式的第二问")
        waitFor(what = "第二次测试模式这一轮生成完") { engine.cacheKeys.size == 3 && !model.state.value.busy }
        val secondTestTurn = engine.cacheKeys[2]

        assertNotEquals("换模式要换键", immersive, firstTestTurn)
        assertNotEquals("退出再进入测试模式，不能沿用上一次测试会话的键", firstTestTurn, secondTestTurn)
        assertTrue("测试模式的键带着模式名", firstTestTurn!!.contains(HearthMode.TEST.name))
    }
}