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
 * P2-1d 诊断：**同一段提示**在「检查点恢复」与「整段重读」两条路上的耗时对照。
 *
 * 为什么这样比：上一版把「第 2 轮 10.6 秒」直接减「读 137 个 token 的估计」得出「恢复要 4–5 秒」，
 * 这个推断是错的——每个 token 的代价随已读长度增长（注意力是 O(n)），第 2 轮的 token 天然比第 1 轮贵。
 * 只有同一段提示、同一温度下两条路的实测差，才能说明检查点到底赚了还是赔了。
 */
@RunWith(AndroidJUnit4::class)
class CheckpointRestoreCostDeviceTest {

    private val context: Application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private val record = LocalModelRecord(
        uri = "content://com.android.externalstorage.documents/document/primary%3AModels%2FQwen3.5-4B-Q4_K_M.gguf",
        name = "Qwen3.5-4B-Q4_K_M.gguf",
        size = 2740937888L,
        sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
        copied = false,
    )

    /** 第一轮：贴近真机规模（约 270 token）。 */
    private fun first() = listOf(
        ChatMessage(ChatRole.SYSTEM, "你是一个安静、说话很短的人，每次只说一句话。"),
        ChatMessage(ChatRole.USER, "今天有点累，陪我说说话。"),
        ChatMessage(ChatRole.USER, ("窗外有雾，桌上有一杯温着的茶，风从窗口进来，屋子里很安静。" ).repeat(8)),
    )

    /** 第二轮：在第一轮后面接上「助手回答 + 新问题」——前缀与第一轮完全一致，所以能被复用。 */
    private fun second() = first() + listOf(
        ChatMessage(ChatRole.ASSISTANT, "那就慢慢说，我在。"),
        ChatMessage(ChatRole.USER, "那你陪我坐会儿。"),
    )

    private suspend fun run(engine: LocalModelEngine, messages: List<ChatMessage>, key: String) {
        val pos = engine.checkpointPositionFor(messages)
        engine.generate(
            messages,
            GenerationParameters(maxTokens = 8, temperature = 0f, seed = 42, cacheKey = key, checkpointPos = pos),
        ).toList()
    }

    @Test
    fun theSamePromptIsTimedWithAndWithoutCheckpoint() = runBlocking {
        val store = AndroidModelStore(context)
        val engine = LocalModelEngine.shared
        val log = mutableListOf<String>()
        try {
            // ---- A 组：开（第 2 轮走恢复）----------------------------------------------------
            engine.checkpointEnabled = true
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 8192)) { store.open(record) }
            run(engine, first(), "cost-a")
            val warmMsA = engine.lastStats?.promptMillis
            run(engine, second(), "cost-a")
            val onStats = engine.lastStats
            val onTimings = engine.checkpointTimings()
            log += "[开] 第 1 轮 promptMs=$warmMsA"
            log += "[开] 第 2 轮 prompt=${onStats?.promptTokens} reused=${onStats?.reusedPromptTokens} " +
                "read=${onStats?.readPromptTokens} promptMs=${onStats?.promptMillis} " +
                "reason=${onStats?.reuseFallbackReason} restoredFrom=${engine.checkpointRestoredFrom}"
            log += "[开] 分段 ms：清空=${onTimings?.getOrNull(0)} set_data=${onTimings?.getOrNull(1)} " +
                "取大小=${onTimings?.getOrNull(2)} 取字节=${onTimings?.getOrNull(3)} JNI=${onTimings?.getOrNull(4)}"

            // ---- B 组：关（同一段提示，整段重读）---------------------------------------------
            engine.unload()
            engine.checkpointEnabled = false
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 8192)) { store.open(record) }
            run(engine, first(), "cost-b")
            val warmMsB = engine.lastStats?.promptMillis
            run(engine, second(), "cost-b")
            val offStats = engine.lastStats
            log += "[关] 第 1 轮 promptMs=$warmMsB"
            log += "[关] 第 2 轮 prompt=${offStats?.promptTokens} reused=${offStats?.reusedPromptTokens} " +
                "read=${offStats?.readPromptTokens} promptMs=${offStats?.promptMillis} reason=${offStats?.reuseFallbackReason}"

            val on = onStats?.promptMillis ?: -1
            val off = offStats?.promptMillis ?: -1
            log += "结论：同一段第 2 轮，开=${on} ms，关=${off} ms，${
                if (on in 1 until off) "检查点快 ${off - on} ms（${"%.2f".format(off.toDouble() / on)} 倍）"
                else "检查点没有赚（慢 ${on - off} ms）"
            }"

            File(context.filesDir, "p2-1d-restore-cost.txt").writeText(
                buildString {
                    appendLine("设备：${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
                    appendLine("线程 8、上下文 8192、温度 0")
                    log.forEach { appendLine(it) }
                },
            )
            assertTrue("要写出诊断文件", File(context.filesDir, "p2-1d-restore-cost.txt").exists())
        } finally {
            engine.unload()
            engine.checkpointEnabled = false
        }
    }
}
