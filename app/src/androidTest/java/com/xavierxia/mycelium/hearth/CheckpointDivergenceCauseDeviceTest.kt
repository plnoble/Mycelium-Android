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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P2-1d 诊断：温度 0 的差异到底来自**状态恢复**还是**预填批次形状**（2026-10-23 夜）。
 *
 * 背景：应用级逐字对照里，开检查点给「想静静地看着你。」，整段重读给「想静静看着窗外的雨。」，
 * 两次运行稳定复现。两种可能：
 *  a) 恢复的状态与真算出来的不完全一致（我们实现的锅）；
 *  b) 状态一致，但「读 31 个 token」与「读 87 个 token」的矩阵乘形状不同，浮点结果末位不同，
 *     温度 0 的 argmax 因此翻转（llama.cpp 的已知特性，任何检查点方案都躲不掉）。
 *
 * 分法：三组对照，全都温度 0、seed 42、同一段提示。
 *  1. **形状相同、不恢复**：第 1 轮读完整段（缓存住 KV），第 2 轮走 P2-1c 的裁尾只读尾部 31 个
 *     ——批次形状与「恢复后读 31 个」完全一样，但状态是**真算出来**的。
 *  2. **形状相同、恢复**：同上，但打开检查点，走恢复路径。
 *  3. **形状不同**：重新加载、整段重读 87 个（无缓存）。
 * 判定：1 与 2 相同 ⇒ 恢复是精确的（锅在 b）；1 与 2 不同 ⇒ 恢复有偏差（锅在 a）。
 */
@RunWith(AndroidJUnit4::class)
class CheckpointDivergenceCauseDeviceTest {

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

    private suspend fun round(engine: LocalModelEngine, messages: List<ChatMessage>, key: String, checkpointPos: Int): String =
        engine.generate(
            messages,
            GenerationParameters(maxTokens = 8, temperature = 0f, seed = 42, cacheKey = key, checkpointPos = checkpointPos),
        ).toList().joinToString("")

    @Test
    fun theSameBatchShapeIsComparedWithAndWithoutRestoring() = runBlocking {
        val store = AndroidModelStore(context)
        val engine = LocalModelEngine.shared
        val log = mutableListOf<String>()
        try {
            // ---- 组 1：形状相同、不恢复（P2-1c 裁尾）------------------------------------------
            engine.checkpointEnabled = false
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 4096)) { store.open(record) }
            val t1First = round(engine, first(), "divergence", checkpointPos = -1)
            val trimOnly = round(engine, second(), "divergence", checkpointPos = -1)
            val trimStats = engine.lastStats
            log += "[形状相同·不恢复] 第 2 轮 reused=${trimStats?.reusedPromptTokens} " +
                "read=${trimStats?.readPromptTokens} reason=${trimStats?.reuseFallbackReason} 正文=$trimOnly"

            // ---- 组 2：形状相同、恢复 ---------------------------------------------------------
            engine.unload()
            engine.checkpointEnabled = true
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 4096)) { store.open(record) }
            val firstForRestore = round(engine, first(), "divergence2", engine.checkpointPositionFor(first()))
            val restored = round(engine, second(), "divergence2", engine.checkpointPositionFor(second()))
            val restoreStats = engine.lastStats
            log += "[形状相同·恢复] 第 1 轮=$firstForRestore reused=${restoreStats?.reusedPromptTokens} " +
                "read=${restoreStats?.readPromptTokens} reason=${restoreStats?.reuseFallbackReason} " +
                "restoredFrom=${engine.checkpointRestoredFrom} 正文=$restored"

            // ---- 组 3：形状不同（整段重读）---------------------------------------------------
            engine.unload()
            engine.checkpointEnabled = false
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 4096)) { store.open(record) }
            round(engine, first(), "divergence3", checkpointPos = -1)
            engine.unload()
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 4096)) { store.open(record) }
            val fullRead = round(engine, second(), "divergence3", checkpointPos = -1)
            val fullStats = engine.lastStats
            log += "[形状不同·整段] 第 2 轮 reused=${fullStats?.reusedPromptTokens} " +
                "read=${fullStats?.readPromptTokens} reason=${fullStats?.reuseFallbackReason} 正文=$fullRead"

            log += ""
            log += "判定：组1 与 组2 是否相同 ⇒ ${if (trimOnly == restored) "相同（恢复是精确的）" else "不同（恢复有偏差）"}"
            log += "判定：组1 与 组3 是否相同 ⇒ ${if (trimOnly == fullRead) "相同" else "不同（批次形状会影响结果）"}"

            File(context.filesDir, "p2-1d-divergence.txt").writeText(
                buildString {
                    appendLine("设备：${android.os.Build.MODEL} / ${android.os.Build.SOC_MODEL}")
                    appendLine("温度 0、seed 42、maxTokens 8")
                    log.forEach { appendLine(it) }
                },
            )
            assertTrue("要写出诊断文件", File(context.filesDir, "p2-1d-divergence.txt").exists())
        } finally {
            runCatching { engine.unload() }
            engine.checkpointEnabled = false
        }
    }
}