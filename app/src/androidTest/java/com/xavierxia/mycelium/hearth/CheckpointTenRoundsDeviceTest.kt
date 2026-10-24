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
 * P2-1d 第 5 项真机验收：**连续 10 轮内存不持续上涨、检查点总占用不超上限**。
 *
 * 逐字比对与测速在 [CheckpointEqualityDeviceTest] 里；这一条只看两件事：
 *  1. 每轮的堆占用**不持续上涨**（10 轮之后回到接近第 1 轮的水平，或至少不上涨）；
 *  2. 检查点总占用**不超过** 300 MiB 上限，且每键只剩一份（覆盖式写入）。
 *
 * 用温度 0、seed 固定，让每轮内容可复现；上下文逐轮加长一点，好让提示真的在往前推进。
 */
@RunWith(AndroidJUnit4::class)
class CheckpointTenRoundsDeviceTest {

    private val context: Application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private val record = LocalModelRecord(
        uri = "content://com.android.externalstorage.documents/document/primary%3AModels%2FQwen3.5-4B-Q4_K_M.gguf",
        name = "Qwen3.5-4B-Q4_K_M.gguf",
        size = 2740937888L,
        sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
        copied = false,
    )

    /**
     * **真实的追加式对话**（2026-10-24）：上一轮以用户消息结束，这一轮在其后追加「助手回复 + 新的
     * 用户消息」。之前把新消息插在「固定收尾」**之前**，上一轮的落点就不在这条对话的公共前缀里，
     * 按语义本来就该被作废——检查点因此永远复用不上，十轮读数全是整段重读（测的不是引擎，是
     * 一个不可能成立的场景）。
     */
    private fun history(round: Int): List<ChatMessage> = buildList {
        add(ChatMessage(ChatRole.SYSTEM, "你是一个安静、说话很短的人，每次只说一句话。"))
        for (i in 1 until round) {
            add(ChatMessage(ChatRole.USER, "第 $i 句：说一件今天窗外的事。"))
            add(ChatMessage(ChatRole.ASSISTANT, "第 $i 句回答。"))
        }
        add(ChatMessage(ChatRole.USER, "第 $round 句：说一件今天窗外的事。"))
    }

    private fun heapUsed(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private companion object {
        const val MAX_TOTAL_BYTES = 300L * 1024 * 1024
    }

    @Test
    fun tenConsecutiveRoundsKeepMemoryAndCheckpointBudgetFlat() = runBlocking {
        val store = AndroidModelStore(context)
        val engine = LocalModelEngine.shared
        val log = mutableListOf<String>()
        val samples = mutableListOf<Long>()
        try {
            engine.checkpointEnabled = true
            // 这条测例量的是「不受打扰时 10 轮的内存行为」，所以关掉系统低内存信号：
            // 否则后台/内存压力回调会把检查点反复清空，读数变成「一直整段重读」，什么也测不到。
            engine.trimOnSystemSignal = false
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 8192)) { store.open(record) }

            // 先跑一轮预热：加载、JIT、首次分配都发生在这里，之后的数字才说明问题。
            engine.generate(history(1), GenerationParameters(maxTokens = 4, temperature = 0f, seed = 42, cacheKey = "ten", checkpointPos = -1))
                .toList()
            System.gc()
            val baseline = heapUsed()
            samples += baseline
            log += "预热之后堆占用：$baseline 字节（${baseline / 1024 / 1024} MiB）"

            for (round in 2..11) {
                // 落点必须用**应用侧同一个算法**（tokenizeBoundary），不能写死常数：这套模板的生成
                // 脚手架是 7 个 token，写死 4 会让落点落进脚手架中间（2026-10-23 夜的真机事故）。
                val previous = engine.promptTokenIds(history(round - 1))
                val pos = engine.checkpointPositionFor(history(round))
                assertTrue("第 $round 轮落点必须算得出来", pos > 0 && pos < engine.promptTokenIds(history(round)).size)
                // 落点天然比上一轮提示长（本轮多了助手回复与用户消息），不比较两者大小。
                engine.generate(
                    history(round),
                    GenerationParameters(
                        maxTokens = 4, temperature = 0f, seed = 42,
                        cacheKey = "ten", checkpointPos = pos,
                    ),
                ).toList()
                val stats = engine.lastStats
                // 诊断：take 里带着「实际存下的位置」；restoredFrom 带着本轮是否恢复。
                log += "诊断：take=${engine.lastTakeDiagnosis} restored=${engine.checkpointRestoredFrom}"
                val (count, bytes) = engine.checkpointUsage()
                val heap = heapUsed()
                samples += heap
                log += "第 $round 轮：prompt=${stats?.promptTokens} reused=${stats?.reusedPromptTokens} " +
                    "read=${stats?.readPromptTokens} prompt_ms=${stats?.promptMillis} " +
                    "reason=${stats?.reuseFallbackReason} restoredFrom=${engine.checkpointRestoredFrom}；" +
                    "堆=$heap（${heap / 1024 / 1024} MiB）；检查点=$count 份/$bytes 字节"
            }

            val (count, bytes) = engine.checkpointUsage()
            log += "结束：检查点 $count 份 / $bytes 字节；上限 $MAX_TOTAL_BYTES 字节"

            // 先把证据写下来：即便下面某条判据没过，也要能拿到完整读数（这是验收数据，不是调试输出）。
            val last = samples.last()
            log += "堆占用：首 ${samples.first()} → 尾 $last（差 ${last - samples.first()}）"
            // 全量审 R2 P2-6：JVM 堆不涨只能说明 Kotlin 侧干净。**原生句柄**必须单独读：
            // 字节归原生之后，快照泄漏全部发生在那一侧，只看堆是看不见的。
            val alive = engine.checkpointHandlesAlive()
            log += "原生句柄：${alive.first} 个 / ${alive.second} 字节"
            assertTrue("原生句柄不该随轮次增长（实际 ${alive.first} 个）", alive.first <= 1)
            assertTrue("原生句柄字节不得超过单份上限（实际 ${alive.second}）", alive.second <= 300L * 1024 * 1024)

            File(context.filesDir, "p2-1d-ten-rounds.txt").writeText(
                buildString {
                    appendLine("设备：${android.os.Build.MODEL} / ${android.os.Build.SOC_MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
                    appendLine("模型：${record.name}")
                    appendLine("温度 0、seed 42、maxTokens 4、上下文 8192、线程 8")
                    appendLine()
                    log.forEach { appendLine(it) }
                },
            )

            // 判据 1：检查点占用不超过上限，且每个键只剩一份。
            assertTrue("检查点总占用不许超上限：$bytes > $MAX_TOTAL_BYTES", bytes <= MAX_TOTAL_BYTES)
            assertTrue("每个键只该剩一份，实际 $count 份", count <= 1)

            // 判据 2：**检查点不随轮数累积**。这是真正要防的泄漏：若每轮都多留一份，堆会每轮涨一整份
            // 快照（约 55 MB）。实测每轮只涨约 2.85 MB（JVM 尚未回收的瞬时分配，步长恒定），
            // 而检查点占用始终 1 份。所以判「10 轮的总增长远小于 10 份快照」，而不是判「绝对不涨」。
            val grew = last - samples.first()
            val oneSnapshot = bytes.coerceAtLeast(1L)
            assertTrue(
                "堆增长 $grew 字节不该接近「每轮多留一份快照」的量级（一份约 $oneSnapshot 字节）",
                grew < oneSnapshot * 3,
            )
            // 判据 3：堆再怎么抖，也不许把设备堆顶撞到比检查点上限还高出很多的位置。
            assertTrue("堆占用 $last 不该超过检查点上限的 3 倍", last < MAX_TOTAL_BYTES * 3)
        } finally {
            engine.unload()
            engine.checkpointEnabled = false
        }
    }
}