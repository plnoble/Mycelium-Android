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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 批次上限矩阵（全量审 P2-10/R2 P2-5 的承接测例）。
 *
 * 固定切法（断批点 = 每条消息结束处，只由 token 序列决定）落地后，这条测例的职责变为：
 * **验证「检查点开关与批次上限都不改变温度 0 的正文」**——
 *  - 开（真恢复）与关（整段重读）必须逐字相同（共同前缀上的批次形状一致）；
 *  - 批次上限 512 与 256 必须逐字相同（这段提示远小于上限，断批由消息边界决定）。
 *
 * 一切断言都是硬性的：恢复失败、复用数对不上、正文为空、正文不一致 ⇒ 测试失败。
 * 证据（读数与正文）写进 files/p2-1d-batch-matrix.txt 供交付报告引用。
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
    ): String = engine.generate(
        messages,
        GenerationParameters(maxTokens = 8, temperature = 0f, seed = 42, cacheKey = key, checkpointPos = checkpointPos),
    ).toList().joinToString("")

    @Test
    fun theSwitchAndTheBatchCapDoNotChangeTheText() = runBlocking {
        val store = AndroidModelStore(context)
        val engine = LocalModelEngine.shared
        val log = mutableListOf<String>()
        try {
            // 开组：真恢复（落点 = 应用侧算法）。
            engine.checkpointEnabled = true
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 8192)) { store.open(record) }
            NativePromptBatch.set(0)
            val openPos1 = engine.checkpointPositionFor(history(1))
            round(engine, history(1), "matrix-open", openPos1)
            val openPos = engine.checkpointPositionFor(history(2))
            val openText = round(engine, history(2), "matrix-open", openPos)
            val openStats = engine.lastStats
            val openRestored = engine.checkpointRestoredFrom
            log += "[open] pos=$openPos restored=$openRestored reused=${openStats?.reusedPromptTokens} " +
                "read=${openStats?.readPromptTokens} reason=${openStats?.reuseFallbackReason} 正文=$openText"
            engine.unload()
            // 开组必须真恢复（全量审 R2 P2-5：没有这条，逐字相同可能只是两条路都在整段重读）。
            assertTrue("开组必须真恢复（restoredFrom=$openRestored）", openRestored > 0)
            assertEquals("复用的 token 数必须等于恢复位置", openRestored.toLong(), openStats?.reusedPromptTokens)
            assertEquals("恢复成功的轮次不该有回退原因", 0L, openStats?.reuseFallbackReason)
            assertTrue("开组正文不能为空", openText.isNotBlank())

            // 关组 ×2 个批次上限：正文都必须与开组逐字相同。
            val closed512 = closedGroup(store, engine, batch = 512, log = log, tag = "512")
            val closed256 = closedGroup(store, engine, batch = 256, log = log, tag = "256")
            assertEquals("批次上限 512 与 256 必须逐字相同", closed512, closed256)
            assertEquals("开与关必须逐字相同（固定切法 ⇒ 共同前缀上的批次一致）", openText, closed512)

            File(context.filesDir, "p2-1d-batch-matrix.txt").writeText(
                buildString {
                    appendLine("设备：${android.os.Build.MODEL} / ${android.os.Build.SOC_MODEL}")
                    appendLine("温度 0、seed 42、maxTokens 8、线程 8、上下文 8192；断批点 = 每条消息结束处")
                    log.forEach { appendLine(it) }
                },
            )
        } finally {
            runCatching { engine.unload() }
            NativePromptBatch.set(0)
            engine.checkpointEnabled = false
        }
    }

    /** 关检查点、指定批次上限，跑两轮并返回第 2 轮正文。 */
    private suspend fun closedGroup(
        store: AndroidModelStore,
        engine: LocalModelEngine,
        batch: Int,
        log: MutableList<String>,
        tag: String,
    ): String {
        engine.checkpointEnabled = false
        NativePromptBatch.set(batch)
        engine.load(record.info, EngineOptions(threads = 8, contextLength = 8192)) { store.open(record) }
        round(engine, history(1), "matrix-$tag", -1)
        val text = round(engine, history(2), "matrix-$tag", -1)
        val stats = engine.lastStats
        log += "[$tag] reused=${stats?.reusedPromptTokens} read=${stats?.readPromptTokens} 正文=$text"
        engine.unload()
        NativePromptBatch.set(0)
        // 关闭组必须整段重读（reused=0），且正文非空。
        assertEquals("关闭组必须整段读（reused=0）", 0L, stats?.reusedPromptTokens)
        assertTrue("关闭组正文不能为空", text.isNotBlank())
        return text
    }
}
