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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P2-1d 对照实验（任务书 2026-10-24 第一节第 1 条）：**批次形状 vs 检查点漏存**，一次跑完四组。
 *
 * 全部用与逐字用例相同的对话（温度 0、seed 42、固定线程数 8、上下文 8192）：
 *  - 组 512：关检查点，n_batch=512（默认）；
 *  - 组 256：关检查点，n_batch=256；
 *  - 组 切一刀：关检查点，在检查点位置强行切一批（batch=落点）；
 *  - 组 开：开检查点（恢复后续读）。
 *
 * 本测例**承接**全量审 P2-10 的归因要求（原 CheckpointDivergenceCauseDeviceTest 已删：
 * 它的「同形状」组实际没构造出来，还给出了超出证据的结论）——这里的「切一刀」组
 * 就是真正的同批次形状对照，且判定只在证据充分时下结论。
 *
 * 判定（写进证据文件，由人核对）：
 *  - 512 与 256 的正文不同，**或**「切一刀」与「开」相同 ⇒ 批次形状坐实，去做「固定切法」；
 *  - 512、256、「切一刀」都相同、只有「开」不同 ⇒ 检查点漏存了状态：停下查漏，报统筹。
 */
@RunWith(AndroidJUnit4::class)
class CheckpointBatchMatrixDeviceTest {

    private val context: Application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private val record = LocalModelRecord(
        uri = "content://com.android.externalstorage.documents/document/primary%3AModels%2FQwen3.5-4B-Q4_K_M.gguf",
        name = "Qwen3.5-4B-Q4_K_M.gguf",
        size = 2740937888L,
        sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
        copied = false,
    )

    /** 与逐字用例完全相同的对话（第 1、2 轮）。 */
    private fun history(turn: Int): List<ChatMessage> = buildList {
        add(ChatMessage(ChatRole.SYSTEM, "你是一个安静、说话很短的人，每次只说一句话。"))
        add(ChatMessage(ChatRole.USER, "今天有点累，陪我说说话。"))
        add(ChatMessage(ChatRole.ASSISTANT, "那就慢慢说，我在。"))
        add(ChatMessage(ChatRole.USER, "用一句话说说你现在的心情。"))
        if (turn >= 2) add(ChatMessage(ChatRole.ASSISTANT, "我在这盏灯下坐着，风从窗口进来。"))
        if (turn >= 2) add(ChatMessage(ChatRole.USER, "那你现在想做什么？"))
    }

    private suspend fun round(
        engine: LocalModelEngine,
        messages: List<ChatMessage>,
        key: String,
        checkpointPos: Int,
    ): Pair<String, String> {
        val text = engine.generate(
            messages,
            GenerationParameters(maxTokens = 8, temperature = 0f, seed = 42, cacheKey = key, checkpointPos = checkpointPos),
        ).toList().joinToString("")
        val s = engine.lastStats
        return text to "reused=${s?.reusedPromptTokens} read=${s?.readPromptTokens} " +
            "promptMs=${s?.promptMillis} reason=${s?.reuseFallbackReason}"
    }

    /** 关检查点、指定批次大小，跑两轮并返回第 2 轮正文与读数。 */
    private suspend fun closedGroup(
        store: AndroidModelStore,
        engine: LocalModelEngine,
        batch: Int,
        cutPosition: Int,
        log: MutableList<String>,
        tag: String,
    ): Pair<String, String> {
        NativePromptBatch.set(batch)
        engine.checkpointEnabled = false
        engine.load(record.info, EngineOptions(threads = 8, contextLength = 8192)) { store.open(record) }
        round(engine, history(1), "matrix-$tag", -1)
        val (text, line) = round(engine, history(2), "matrix-$tag", -1)
        engine.unload()
        log += "[$tag] 第 2 轮：$line 正文=$text"
        return text to line
    }

    @Test
    fun theFourGroupsAreComparedOnTheSameConversation() = runBlocking {
        val store = AndroidModelStore(context)
        val engine = LocalModelEngine.shared
        val log = mutableListOf<String>()
        try {
            // 先用「开检查点」那组把检查点位置量出来（切一刀组要用同一个位置）。
            NativePromptBatch.set(0)
            engine.checkpointEnabled = true
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 8192)) { store.open(record) }
            val cut = engine.checkpointPositionFor(history(2))
            round(engine, history(1), "matrix-open", engine.checkpointPositionFor(history(1)))
            val (openText, openLine) = round(engine, history(2), "matrix-open", cut)
            engine.unload()
            log += "[开] 第 2 轮（落点=$cut）：$openLine 正文=$openText"

            // 三组关检查点的对照。
            val (x512, l512) = closedGroup(store, engine, batch = 512, cutPosition = cut, log = log, tag = "512")
            val (x256, l256) = closedGroup(store, engine, batch = 256, cutPosition = cut, log = log, tag = "256")
            val (xCut, lCut) = closedGroup(store, engine, batch = cut, cutPosition = cut, log = log, tag = "切一刀@$cut")

            NativePromptBatch.set(0)
            log += ""
            log += "判定："
            log += "512 vs 256：${if (x512 == x256) "相同" else "不同 ⇒ 批次形状坐实"}"
            log += "切一刀 vs 开：${if (xCut == openText) "相同 ⇒ 与开检查点一致（批次形状坐实）" else "不同"}"
            log += "512 vs 开：${if (x512 == openText) "相同" else "不同（即用户看到的逐字差异）"}"
            val shapeConfirmed = (x512 != x256) || (xCut == openText)
            val leakSuspected = (x512 == x256) && (xCut != openText)
            log += if (shapeConfirmed) {
                "总判定：**批次形状坐实** ⇒ 按 task 做固定切法"
            } else if (leakSuspected) {
                "总判定：**疑似检查点漏存状态** ⇒ 停下查漏，报统筹"
            } else {
                "总判定：结果不典型，人工核对上文四组正文"
            }

            File(context.filesDir, "p2-1d-batch-matrix.txt").writeText(
                buildString {
                    appendLine("设备：${android.os.Build.MODEL} / ${android.os.Build.SOC_MODEL}")
                    appendLine("温度 0、seed 42、maxTokens 8、线程 8、上下文 8192")
                    log.forEach { appendLine(it) }
                },
            )
            assertTrue("要写出对照矩阵", File(context.filesDir, "p2-1d-batch-matrix.txt").exists())
        } finally {
            runCatching { engine.unload() }
            NativePromptBatch.set(0)
            engine.checkpointEnabled = false
        }
    }
}