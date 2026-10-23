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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P2-1d：**运行时参数扫描**（线程数 × 上下文长度），替用户把「哪个设置最合适」测出来。
 *
 * 只测这一份构建真能调的参数：CPU 线程数、上下文长度。GPU 层数 / Hexagon / 批大小要么没有后端、
 * 要么是编译期常量，界面上不暴露（批大小已固定在 512）。
 *
 * **线程数超过核数会灾难性变慢**（2026-10-23 实测，本机 8 核）：10 线程生成降到 0.18 tok/s、
 * 12 线程 0.17、16 线程 0.09（8 线程是 10.25）——ggml 每个线程自旋等栅栏，超订后全在抢核。
 * 所以扫描范围只到「核数」为止，界面上也不该让人填更大的值。
 *
 * 判据分两段量：
 *  - 读提示（promptMs）：聊天里「按下发送到第一个字」的等待，越大越明显；
 *  - 生成（genTokPerSec）：出字速度，长回答的体感。
 */
@RunWith(AndroidJUnit4::class)
class EngineParameterSweepDeviceTest {

    private val context: Application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private val record = LocalModelRecord(
        uri = "content://com.android.externalstorage.documents/document/primary%3AModels%2FQwen3.5-4B-Q4_K_M.gguf",
        name = "Qwen3.5-4B-Q4_K_M.gguf",
        size = 2740937888L,
        sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
        copied = false,
    )

    /** 固定的一段中等长度对话（约 300 token），每档都用它，才可比。 */
    private fun history() = listOf(
        ChatMessage(ChatRole.SYSTEM, "你是一个安静、说话很短的人，每次只说一句话。"),
        ChatMessage(ChatRole.USER, "今天有点累，陪我说说话。"),
        ChatMessage(ChatRole.USER, ("窗外有雾，桌上有一杯温着的茶，风从窗口进来，屋子里很安静。" ).repeat(9)),
        ChatMessage(ChatRole.USER, "用一句话说说你现在的心情。"),
    )

    @Test
    fun theBestThreadAndContextCombinationIsMeasured() = runBlocking {
        val store = AndroidModelStore(context)
        val engine = LocalModelEngine.shared
        val log = mutableListOf<String>()
        log += "模型：${record.name}（${record.size} 字节）"
        log += "批大小 512、flash attn AUTO、ARM dotprod/i8mm/fp16"
        log += ""
        log += "线程  上下文  提示 token  读提示 ms  每 token ms  生成 tok/s"
        try {
            for (threads in listOf(4, 6, 8)) {
                for (contextLength in listOf(4096, 8192)) {
                    val result = runCatching {
                        engine.load(record.info, EngineOptions(threads = threads, contextLength = contextLength)) {
                            store.open(record)
                        }
                        engine.generate(
                            history(),
                            GenerationParameters(maxTokens = 32, temperature = 0f, seed = 42),
                        ).toList()
                        engine.lastStats
                    }
                    val stats = result.getOrNull()
                    if (stats == null) {
                        log += "$threads  $contextLength  加载或生成失败"
                        continue
                    }
                    val perToken = if (stats.readPromptTokens > 0) {
                        "%.1f".format(stats.promptMillis.toDouble() / stats.readPromptTokens)
                    } else "?"
                    log += "$threads  $contextLength  ${stats.promptTokens}  ${stats.promptMillis}  " +
                        "$perToken  ${"%.2f".format(stats.tokensPerSecond)}"
                    engine.unload()
                }
            }
            File(context.filesDir, "p2-1d-sweep.txt").writeText(
                buildString {
                    appendLine("设备：${android.os.Build.MODEL} / ${android.os.Build.SOC_MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
                    log.forEach { appendLine(it) }
                },
            )
        } finally {
            runCatching { engine.unload() }
        }
    }
}
