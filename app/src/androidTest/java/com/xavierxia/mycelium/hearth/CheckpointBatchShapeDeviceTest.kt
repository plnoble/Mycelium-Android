package com.xavierxia.mycelium.hearth

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xavierxia.mycelium.llm.AndroidModelStore
import com.xavierxia.mycelium.llm.EngineOptions
import com.xavierxia.mycelium.llm.LocalModelEngine
import com.xavierxia.mycelium.llm.LocalModelRecord
import com.xavierxia.mycelium.llm.NativePromptBatch
import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.ChatRole
import com.xavierxia.mycelium.model.GenerationParameters
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 温度 0 差异归因（2026-10-24）：把「整段重读」也切成与「恢复后读」相同的批次形状，再比正文。
 *
 * 如果切了批次之后两条路的正文一致，说明恢复的状态是精确的，差异来自矩阵乘形状的浮点末位；
 * 如果仍然不一致，那就是恢复的状态与真算出来的不同——要在实现里查。
 */
@RunWith(AndroidJUnit4::class)
class CheckpointBatchShapeDeviceTest {

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
        ChatMessage(ChatRole.SYSTEM, "你是一个安静、说话很短的人。"),
        ChatMessage(ChatRole.USER, "今天有点累"),
    )

    private fun second() = first() + listOf(
        ChatMessage(ChatRole.ASSISTANT, "安静地陪着你。"),
        ChatMessage(ChatRole.USER, "那就好"),
    )

    private suspend fun round(engine: LocalModelEngine, m: List<ChatMessage>, key: String, pos: Int): String =
        engine.generate(m, GenerationParameters(maxTokens = 8, temperature = 0f, seed = 42, cacheKey = key, checkpointPos = pos))
            .toList().joinToString("")

    @Test
    fun theSameShapesAreCompared() = runBlocking {
        val store = AndroidModelStore(context)
        val engine = LocalModelEngine.shared
        val log = mutableListOf<String>()
        try {
            // A：恢复后只读尾部（一次提交 31 个）。
            engine.checkpointEnabled = true
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 4096)) { store.open(record) }
            round(engine, first(), "shape", engine.checkpointPositionFor(first()))
            val restored = round(engine, second(), "shape", engine.checkpointPositionFor(second()))
            val aStats = engine.lastStats
            log += "A 恢复：reused=${aStats?.reusedPromptTokens} read=${aStats?.readPromptTokens} 正文=$restored"
            engine.unload()

            // B：整段重读，但批次限制成与 A 的"已复用部分"相同（这样尾部那次提交的 token 数相近）。
            engine.checkpointEnabled = false
            NativePromptBatch.set(56)
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 4096)) { store.open(record) }
            round(engine, first(), "shape2", -1)
            val split = round(engine, second(), "shape2", -1)
            val bStats = engine.lastStats
            log += "B 分批整段：reused=${bStats?.reusedPromptTokens} read=${bStats?.readPromptTokens} 正文=$split"
            engine.unload()
            NativePromptBatch.set(0)

            log += ""
            log += "判定：A 与 B ${if (restored == split) "正文相同 ⇒ 恢复是精确的，差异来自批次形状" else "仍不同 ⇒ 恢复的状态与真算出来的不一致"}"

            File(context.filesDir, "p2-1d-batch-shape.txt").writeText(
                buildString {
                    appendLine("设备：${android.os.Build.MODEL}")
                    log.forEach { appendLine(it) }
                },
            )
        } finally {
            runCatching { engine.unload() }
            engine.checkpointEnabled = false
        }
    }
}
