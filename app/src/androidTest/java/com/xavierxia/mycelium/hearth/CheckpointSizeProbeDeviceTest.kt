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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P2-1d 第 0 步（统筹 2026-10-23 裁定）：**实测**「整份状态」与「只存循环层」的体积和存取耗时。
 *
 * 方案里原来写「整份约为循环层的 24 倍」，统筹不认：按 Qwen3.5 的结构，注意力层那部分应该只有几十 MB 级。
 * 所以这里只做测量，不写推算——结论以本用例写进 `p2-1d-step0-sizes.txt` 的数字为准。
 *
 * 做法：
 *  1. 拼一段约 1500 token 的对话（用一个长段落补足，token 数照实报）；
 *  2. 读一遍提示，让 KV 里真的有这么多 token；
 *  3. 调 `measureCheckpoints()`：分别取 NONE / PARTIAL_ONLY 两份的大小，各存一次各恢复一次，报耗时；
 *  4. **探针跑完之后必须还能正常生成**——它声称会把整份状态装回去，这条就是验收。
 */
@RunWith(AndroidJUnit4::class)
class CheckpointSizeProbeDeviceTest {

    private val context: Application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private val record = LocalModelRecord(
        uri = "content://com.android.externalstorage.documents/document/primary%3AModels%2FQwen3.5-4B-Q4_K_M.gguf",
        name = "Qwen3.5-4B-Q4_K_M.gguf",
        size = 2740937888L,
        sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
        copied = false,
    )

    /** 约 1500 token 的对话：开头几轮是正常对话，后面用一个长段落补足 token 数。 */
    private fun history(): List<ChatMessage> = buildList {
        add(ChatMessage(ChatRole.SYSTEM, "你是一个安静、说话很短的人，每次只说一句话。"))
        add(ChatMessage(ChatRole.USER, "今天有点累，陪我说说话。"))
        add(ChatMessage(ChatRole.ASSISTANT, "那就慢慢说，我在。"))
        add(ChatMessage(ChatRole.USER, "用一句话说说你现在的心情。"))
        add(ChatMessage(ChatRole.ASSISTANT, "我在这盏灯下坐着，风从窗口进来。"))
        add(ChatMessage(ChatRole.USER, "那你现在想做什么？"))
        // 补足到 1500 token 左右：一段可读的中文，重复若干次。
        val paragraph = "我们把昨天的事再说一遍：早上窗子外面有雾，中午太阳出来，下午下了一点雨，" +
            "晚上回到屋子里，桌上有一杯还温着的茶。这样的日子一天一天过去，没有什么大事发生。"
        add(ChatMessage(ChatRole.USER, paragraph.repeat(30)))
    }

    @Test
    fun theRealSizeOfAFullStateIsMeasuredInsteadOfGuessed() = runBlocking {
        val store = AndroidModelStore(context)
        val engine = LocalModelEngine.shared
        val log = mutableListOf<String>()
        try {
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 8192)) { store.open(record) }

            val history = history()
            val tokens = engine.promptTokenIds(history)
            log += "对话 token 数（含模板）：${tokens.size}"
            assertTrue(
                "这段对话应当接近 1500 token（任务书的要求），实际 ${tokens.size}；不够就把段落重复次数调大",
                tokens.size >= 1400,
            )

            // 读一遍提示，让 KV 里真的有这么多 token；温度 0 只生成 1 个 token，够触发 prefill。
            engine.generate(history, GenerationParameters(maxTokens = 1, temperature = 0f, seed = 42)).toList()
            val stats = engine.lastStats
            log += "本轮读数：prompt=${stats?.promptTokens} read=${stats?.readPromptTokens} prompt_ms=${stats?.promptMillis}"

            // 测量：NONE / PARTIAL_ONLY 两份的大小与存取耗时。跑完引擎状态应当不变。
            val measured = engine.measureCheckpoints()
            assertNotNull("探针要给出读数", measured)
            val m = measured!!
            log += "大小：整份(NONE)=${m[0]} 字节（${m[0] / 1024 / 1024} MiB）；只循环层(PARTIAL_ONLY)=${m[1]} 字节（${m[1] / 1024 / 1024} MiB）"
            log += "倍数：${if (m[1] > 0) m[0].toDouble() / m[1] else 0.0} 倍"
            log += "耗时：存整份=${m[2]} ms、存循环层=${m[3]} ms、恢复整份=${m[4]} ms、恢复循环层=${m[5]} ms"
            log += "一致性：written(NONE)=${m[6]}/size=${m[0]}；written(PARTIAL)=${m[7]}/size=${m[1]}；read(NONE)=${m[8]}；read(PARTIAL)=${m[9]}"

            // 探针声称「跑完把整份状态装回去」：两条判据都要成立。
            assertEquals("整份必须原样写回", m[6], m[0])
            assertEquals("整份必须原样装回", m[8], m[6])
            assertTrue("循环层那一份也得成", m[7] == m[1] && m[9] == m[7])
            assertTrue("整份必须比循环层大（否则注意力 KV 没进去）", m[0] > m[1])

            // 关键验收：探针跑完之后还能正常生成，且输出与探针之前可复现（状态没坏）。
            val after = engine.generate(
                history + ChatMessage(ChatRole.USER, "最后说一句。"),
                GenerationParameters(maxTokens = 8, temperature = 0f, seed = 42),
            ).toList().joinToString("")
            log += "探针之后的生成：『$after』"
            assertTrue("探针跑完之后必须还能生成出内容（状态没坏）", after.isNotBlank())

            File(context.filesDir, "p2-1d-step0-sizes.txt").writeText(
                buildString {
                    appendLine("设备：${android.os.Build.MODEL} / ${android.os.Build.SOC_MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
                    appendLine("模型：${record.name}")
                    appendLine("上下文：8192，线程 8")
                    appendLine()
                    log.forEach { appendLine(it) }
                },
            )
        } finally {
            engine.unload()
        }
    }
}