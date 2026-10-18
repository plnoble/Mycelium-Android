package com.xavierxia.mycelium.hearth

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xavierxia.mycelium.llm.AndroidModelStore
import com.xavierxia.mycelium.llm.LocalModelEngine
import com.xavierxia.mycelium.model.ChatRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 第 5 条：JNI 加载已经成功、主线程还没恢复、页面还显示「正在加载」的时候点停止。原来这一下
 * `userStopped=true`、但 `engine.load()` 不一定抛异常，随后照样 `generate()`，而且本地回答在
 * `userStopped` 时还允许入库 —— 问题没回到输入框，回答却可能被存下。
 *
 * 现在加载返回之后、开始生成之前会再问一次这一轮的停止意图，所以两条路（native 抛
 * `Loading stopped`、或 native 照常成功返回后被引擎/轮次认下）都收在同一个结果上：问题回输入框、
 * 一个字都不入库。这一例钉的就是那个结果 —— 不管哪条路先赢。
 *
 * 需要真机上已经登记过模型（和设备上的其它本地模型测试一样）；没有就跳过，不假装通过。
 */
@RunWith(AndroidJUnit4::class)
class HearthLocalStopDuringLoadDeviceTest {

    @Test
    fun stoppingWhileTheLocalModelLoadsNeverStoresAnAnswer() = runBlocking {
        val context = hearthApplication()
        val store = AndroidModelStore(context)
        val found = store.registry.list().firstOrNull()
        assumeTrue("真机上还没有登记过本地模型，先导入一个再跑这一例", found != null)
        val registered = requireNotNull(found)

        val database = HearthDatabase(context)
        val characters = CharacterStore { database.session() }
        val log = ConversationLog { database.session() }
        val tag = System.currentTimeMillis().toString().takeLast(6)
        val characterName = "加载期停止复验人物 $tag"
        val question = "加载期停止复验：这句会回到输入框吗 $tag"
        var characterId: String? = null
        try {
            characterId = characters.create(
                CharacterDraft(
                    name = characterName,
                    personality = "真机复验用的临时人物，测试结束就删掉。",
                    greeting = "在的。",
                    modelKind = ModelKind.LOCAL,
                    modelId = registered.sha256,
                    modelName = registered.name,
                    maxTokens = 256,
                ),
                hearthNow(),
            ).id
            val character = requireNotNull(characters.character(requireNotNull(characterId)))
            val model = HearthViewModel(context)

            model.openChat(character)
            hearthWaitUntil(20_000) { !model.state.value.historyLoading }

            // 加载一个没加载过的模型：4B Q4 的加载要好几秒，停止点一定落在「正在加载」里。
            LocalModelEngine.shared.unload()
            hearthWaitUntil(20_000) { LocalModelEngine.shared.loadedModel.value == null }
            model.send(question)
            hearthWaitUntil(20_000) { model.state.value.notice?.contains("正在加载") == true }
            model.stop()

            hearthWaitUntil(60_000) { !model.state.value.busy }
            assertTrue(
                "加载期停止之后问题必须回到输入框",
                model.state.value.pendingQuestion == question,
            )
            assertFalse(
                "加载期停止之后不该还显示生成中",
                model.state.value.busy,
            )
            assertTrue(
                "加载期停止之后这一轮一个字都不该入库",
                log.conversations(character.id).isEmpty(),
            )
            assertTrue(
                "加载期停止之后不该有回答消息",
                log.conversations(character.id).flatMap { log.messages(it.id) }.none { it.role == ChatRole.ASSISTANT },
            )
        } finally {
            characterId?.let { characters.delete(it) }
            LocalModelEngine.shared.unload()
        }
    }
}

/** 一直在应用自己的 Application 上跑，人物与对话都进真实（加密）库。 */
private fun hearthApplication(): Application =
    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

/** 人物与对话的时间戳用应用自己的格式。 */
private fun hearthNow(): String = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

/** 等一个条件成立；等不到就让断言失败，而不是让测试挂住。 */
private fun hearthWaitUntil(timeoutMs: Long, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(50)
    }
    assertTrue("等待超时（${timeoutMs}ms）：条件一直没成立", condition())
}