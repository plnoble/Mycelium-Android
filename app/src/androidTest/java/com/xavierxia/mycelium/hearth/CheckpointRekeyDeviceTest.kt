package com.xavierxia.mycelium.hearth

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xavierxia.mycelium.llm.AndroidModelStore
import com.xavierxia.mycelium.llm.EngineOptions
import com.xavierxia.mycelium.llm.LocalModelEngine
import com.xavierxia.mycelium.llm.LocalModelRecord
import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.ChatRole
import com.xavierxia.mycelium.model.GenerationParameters
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P2-1d：**第 2 轮用得上检查点**（2026-10-23 夜，任务书第一段第 2 条的应用级证明）。
 *
 * 复现应用里那段真实的键生命周期，不需要人点界面：
 *  1. 新会话第一轮还没有正式编号 ⇒ 键里用临时会话；
 *  2. 这一轮落库、拿到正式编号 ⇒ 调 `rekeyCheckpoints`（`HearthViewModel` 在同一个位置做的同一件事）；
 *  3. 第二轮用正式编号的键 ⇒ 必须复用第一轮存下的检查点，而不是整段重读。
 *
 * 真机修复前的读数：第 2 轮 `reused=0 read=160 reason=5`（整段重读）。修好之后应为
 * `reused>0 read<prompt reason=0`。
 */
@RunWith(AndroidJUnit4::class)
class CheckpointRekeyDeviceTest {

    private val context: Application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private val record = LocalModelRecord(
        uri = "content://com.android.externalstorage.documents/document/primary%3AModels%2FQwen3.5-4B-Q4_K_M.gguf",
        name = "Qwen3.5-4B-Q4_K_M.gguf",
        size = 2740937888L,
        sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
        copied = false,
    )

    private fun first() = listOf(
        ChatMessage(ChatRole.SYSTEM, "你是一个安静、说话很短的人，每次只说一句话。"),
        ChatMessage(ChatRole.USER, "今天有点累，陪我说说话。"),
        ChatMessage(ChatRole.USER, ("窗外有雾，桌上有一杯温着的茶，风从窗口进来，屋子里很安静。").repeat(8)),
    )

    private fun second() = first() + listOf(
        ChatMessage(ChatRole.ASSISTANT, "那就慢慢说，我在。"),
        ChatMessage(ChatRole.USER, "那你陪我坐会儿。"),
    )

    private suspend fun round(engine: LocalModelEngine, messages: List<ChatMessage>, key: String) {
        val pos = engine.checkpointPositionFor(messages)
        engine.generate(
            messages,
            GenerationParameters(maxTokens = 8, temperature = 0f, seed = 42, cacheKey = key, checkpointPos = pos),
        ).toList()
    }

    @Test
    fun theSecondRoundReusesTheCheckpointStoredUnderTheTemporaryKey() = runBlocking {
        val store = AndroidModelStore(context)
        val engine = LocalModelEngine.shared
        val log = mutableListOf<String>()
        try {
            engine.checkpointEnabled = true
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 4096)) { store.open(record) }

            // 第 1 轮：临时键（应用里就是这个样子）。
            round(engine, first(), "hearth|人物A|temp-1|model")
            val firstStats = engine.lastStats
            log += "第 1 轮（临时键）：prompt=${firstStats?.promptTokens} " +
                "reused=${firstStats?.reusedPromptTokens} read=${firstStats?.readPromptTokens} " +
                "reason=${firstStats?.reuseFallbackReason} savedPos=${firstStats?.checkpointPos}"

            // 落库拿到正式编号 ⇒ 改挂（应用在 chat.finish 之后做同一件事）。
            engine.rekeyCheckpoints("hearth|人物A|temp-1|model", "hearth|人物A|conv-42|model")

            // 第 2 轮：正式键。
            round(engine, second(), "hearth|人物A|conv-42|model")
            val secondStats = engine.lastStats
            log += "第 2 轮（正式键）：prompt=${secondStats?.promptTokens} " +
                "reused=${secondStats?.reusedPromptTokens} read=${secondStats?.readPromptTokens} " +
                "reason=${secondStats?.reuseFallbackReason} restoredFrom=${engine.checkpointRestoredFrom} " +
                "promptMs=${secondStats?.promptMillis}"

            File(context.filesDir, "p2-1d-rekey.txt").writeText(
                buildString {
                    appendLine("设备：${android.os.Build.MODEL} / ${android.os.Build.SOC_MODEL}")
                    log.forEach { appendLine(it) }
                },
            )

            assertEquals("第 2 轮不该回退", 0L, secondStats?.reuseFallbackReason)
            assertTrue(
                "第 2 轮必须复用第一轮的落点（reused=${secondStats?.reusedPromptTokens}，" +
                    "restoredFrom=${engine.checkpointRestoredFrom}）",
                (secondStats?.reusedPromptTokens ?: 0) > 0,
            )
            assertTrue(
                "复用了就不该整段重读：read=${secondStats?.readPromptTokens} prompt=${secondStats?.promptTokens}",
                (secondStats?.readPromptTokens ?: 0) < (secondStats?.promptTokens ?: 0),
            )
        } finally {
            engine.unload()
            engine.checkpointEnabled = false
        }
    }
}