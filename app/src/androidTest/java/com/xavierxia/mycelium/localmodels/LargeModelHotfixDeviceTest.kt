package com.xavierxia.mycelium.localmodels

import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * 0.5.1 热修的真机验收：超过 4 GiB 的模型不再做 CPU 权重重排。
 *
 * 用调试包里已经登记的模型：所有超过 4 GiB 的，加上最小的一个做对照（它照旧重排）。
 * 走炉边的真实路径：打开检查点开关，每轮按模型算检查点落点，下一轮的历史里放上一轮真实生成的回答。
 * 测例结束时把检查点开关恢复成原来的值。
 *
 * 每个模型要过的条件：
 *  - 实际打开的文件大小等于登记的大小（「超过 4 GiB」按实际文件判断）；
 *  - 加载后的匿名内存（驻留的 RssAnon 加上换出的 VmSwap）：超过 4 GiB 的模型不到文件大小的四分之一，
 *    照旧重排的对照超过文件大小的一半。读不到这两项就算失败。这是佐证，不是结构证明：
 *    结构上的依据是 engine.cpp 对超过 4 GiB 的文件关掉了 use_extra_bufts；
 *  - 连聊 3 轮，每轮都有可见回答、没有被停止；
 *  - 支持检查点的模型（混合 / 循环架构）：每轮都算出落点，轮末仓库里确实存着检查点，
 *    第 2 轮起真的从检查点恢复。不支持的模型照生产路径不存检查点，不查这几条。
 *
 * 完整验收（不给 minBytes）必须有一个照旧重排的对照，且至少有一个支持检查点的模型。
 *
 * 默认用 App 的默认设置（8 线程、上下文 8192）。对照用的参数：
 * -e threads N、-e context N、-e minBytes N（只测不小于这个字节数的大模型，不测对照）。
 * 模型文件只读；报告写进调试包私有目录，不写文件名，只写大小和序号；开跑前先删掉上一次的报告。
 * 是否通过以 instrumentation 的结果为准，报告只是读数。
 */
@RunWith(AndroidJUnit4::class)
class LargeModelHotfixDeviceTest {
    private val fourGiB = 4L * 1024 * 1024 * 1024

    private fun status(): Map<String, Long> = File("/proc/self/status").readLines()
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2 && parts[0].endsWith(":")) parts[0].dropLast(1) to (parts[1].toLongOrNull() ?: return@mapNotNull null)
            else null
        }.toMap()

    private fun memory(): String {
        val s = status()
        return listOf("VmHWM", "VmRSS", "RssAnon", "RssFile", "VmSwap").joinToString(" ") { "$it: ${s[it]} kB" }
    }

    private val questions = listOf("今天下雨了，你在做什么？", "那你喜欢雨天吗？为什么？", "说一件雨天让你开心的小事。")

    @Test
    fun largeModelsSurviveThreeRounds() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = File(context.filesDir, "hotfix-0.5.1-large-models.txt")
        report.delete()
        val runId = UUID.randomUUID().toString().take(8)
        val lines = mutableListOf("运行 $runId：开始 ${memory()}")
        fun flush() = report.writeText(lines.joinToString("\n") + "\n")
        flush()

        // 可选参数（am instrument -e ...），用来对照被杀的条件；不给就是 App 默认设置、全部模型。
        val args = InstrumentationRegistry.getArguments()
        val defaults = EngineOptions()
        val options = EngineOptions(
            threads = args.getString("threads")?.toIntOrNull() ?: defaults.threads,
            contextLength = args.getString("context")?.toIntOrNull() ?: defaults.contextLength,
        )
        val minBytes = args.getString("minBytes")?.toLongOrNull()
        val store = AndroidModelStore(context)
        val registered = store.registry.list()
        val large = registered.filter { it.size > fourGiB }.sortedBy { it.size }
        val control = registered.filter { it.size <= fourGiB }.minByOrNull { it.size }
        assertTrue("调试包里没有登记超过 4 GiB 的模型", large.isNotEmpty())
        if (minBytes == null) assertNotNull("完整验收要有一个照旧重排的对照模型（不超过 4 GiB）", control)
        val models: List<LocalModelRecord> =
            if (minBytes == null) listOfNotNull(control) + large else large.filter { it.size >= minBytes }
        assertTrue("没有符合 minBytes 的模型", models.isNotEmpty())
        lines += "设备：${android.os.Build.MODEL}；设置 $options；模型 ${models.size} 个"
        flush()

        val engine = LocalModelEngine.shared
        val checkpointWas = engine.checkpointEnabled
        engine.checkpointEnabled = true
        var checkpointModels = 0
        try {
            for ((index, model) in models.withIndex()) {
                var openedSize = -1L
                val started = SystemClock.elapsedRealtime()
                try {
                    engine.load(model.info, options) {
                        // 探针出错时由这里关掉原文件；成功以后才把它交给引擎。
                        val file = store.open(model)
                        try {
                            openedSize = ParcelFileDescriptor.fromFd(file.fd).use { it.statSize }
                        } catch (e: Throwable) {
                            file.close()
                            throw e
                        }
                        file
                    }
                    val loadMs = SystemClock.elapsedRealtime() - started
                    val repacked = openedSize <= fourGiB
                    val supportsCheckpoint = engine.canCheckpoint()
                    if (supportsCheckpoint) checkpointModels++
                    val label = "模型${index + 1}（${String.format(Locale.ROOT, "%.2f", openedSize / 1e9)} GB，" +
                        "${if (repacked) "对照，照旧重排" else "超过 4 GiB，不重排"}）"
                    val s = status()
                    val anonKb = checkNotNull(s["RssAnon"]) { "读不到 RssAnon" }
                    val swapKb = checkNotNull(s["VmSwap"]) { "读不到 VmSwap" }
                    val anonBytes = (anonKb + swapKb) * 1024
                    lines += "$label 加载 $loadMs ms；支持检查点 $supportsCheckpoint；" +
                        "匿名内存（驻留加换出）占文件 ${String.format(Locale.ROOT, "%.0f", 100.0 * anonBytes / openedSize)}%；${memory()}"
                    flush()
                    assertEquals("实际打开的文件大小要等于登记的大小", model.size, openedSize)
                    if (repacked) {
                        assertTrue("对照要照旧重排：匿名内存应超过文件的一半，否则换一个已知会重排的模型做对照", anonBytes > openedSize / 2)
                    } else {
                        assertTrue("超过 4 GiB 的模型不该有重排副本：匿名内存应不到文件的四分之一", anonBytes < openedSize / 4)
                    }

                    val history = mutableListOf(ChatMessage(ChatRole.SYSTEM, "你是一个温和、话不多的朋友，用中文简短回答。"))
                    for (round in 1..3) {
                        val messages = history + ChatMessage(ChatRole.USER, questions[round - 1])
                        val pos = if (supportsCheckpoint) engine.checkpointPositionFor(messages) else -1
                        val text = withTimeout(300_000) {
                            engine.generate(
                                messages,
                                GenerationParameters(
                                    maxTokens = 128, temperature = 0f, seed = 42,
                                    cacheKey = "hotfix-0.5.1-$runId-$index", checkpointPos = pos,
                                ),
                            ).toList().joinToString("")
                        }
                        val stats = checkNotNull(engine.lastStats)
                        val restoredFrom = engine.checkpointRestoredFrom
                        val (storedCount, storedBytes) = engine.checkpointUsage()
                        lines += "$label 第${round}轮：提示 ${stats.promptTokens}（复用 ${stats.reusedPromptTokens}，实读 ${stats.readPromptTokens}，" +
                            "从检查点 $restoredFrom 恢复）用 ${stats.promptMillis} ms；生成 ${stats.generatedTokens} 个，" +
                            "${String.format(Locale.ROOT, "%.2f", stats.tokensPerSecond)} token/秒；可见回答 ${text.trim().length} 字；" +
                            "落点 $pos，原生捕获 ${stats.checkpointPos}（${stats.checkpointBytes / 1_000_000} MB），" +
                            "轮末仓库 $storedCount 份（${storedBytes / 1_000_000} MB）；${memory()}"
                        flush()
                        assertTrue("第${round}轮要有可见回答", text.isNotBlank())
                        assertFalse("第${round}轮不该被停止", stats.stopped)
                        if (supportsCheckpoint) {
                            assertTrue("第${round}轮要算出检查点落点", pos > 0)
                            assertTrue("第${round}轮结束时仓库里要存着检查点", storedCount >= 1 && storedBytes > 0)
                            if (round >= 2) {
                                assertTrue("第${round}轮要真的从检查点恢复", restoredFrom > 0)
                                assertEquals("复用的 token 数要等于恢复位置", restoredFrom.toLong(), stats.reusedPromptTokens)
                                assertEquals("恢复成功的轮次不该有回退原因", 0L, stats.reuseFallbackReason)
                            }
                        }
                        history += ChatMessage(ChatRole.USER, questions[round - 1])
                        history += ChatMessage(ChatRole.ASSISTANT, text)
                    }
                } finally {
                    engine.unload()
                }
            }
        } finally {
            engine.checkpointEnabled = checkpointWas
        }
        if (minBytes == null) assertTrue("完整验收要至少有一个支持检查点的模型", checkpointModels > 0)
        lines += "运行 $runId：全部通过；结束 ${memory()}"
        flush()
    }
}
