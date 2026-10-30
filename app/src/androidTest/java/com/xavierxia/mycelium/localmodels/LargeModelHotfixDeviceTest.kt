package com.xavierxia.mycelium.localmodels

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * 0.5.1 热修的真机验收：超过 4 GiB 的模型不做 CPU 权重重排以后，连聊三轮不再被系统杀掉。
 *
 * 用调试包里已经登记的模型：所有超过 4 GiB 的，加上最小的一个做对照（它照旧重排，速度不该变）。
 * 用 App 的默认设置（8 线程、上下文 8192），每轮都带缓存键，和炉边一样走检查点复用。
 * 模型文件只读；报告写进调试包私有目录，不写文件名，只写大小和序号。
 */
@RunWith(AndroidJUnit4::class)
class LargeModelHotfixDeviceTest {
    private val fourGiB = 4L * 1024 * 1024 * 1024

    private fun memory(): String {
        val wanted = setOf("VmHWM", "VmRSS", "RssAnon", "RssFile")
        return File("/proc/self/status").readLines()
            .filter { line -> wanted.any { line.startsWith("$it:") } }
            .joinToString(" ") { it.replace(Regex("\\s+"), " ") }
    }

    private fun conversation(round: Int): List<ChatMessage> = buildList {
        add(ChatMessage(ChatRole.SYSTEM, "你是一个温和、话不多的朋友，用中文简短回答。"))
        val questions = listOf("今天下雨了，你在做什么？", "那你喜欢雨天吗？为什么？", "说一件雨天让你开心的小事。")
        for (i in 0 until round) {
            add(ChatMessage(ChatRole.USER, questions[i]))
            if (i < round - 1) add(ChatMessage(ChatRole.ASSISTANT, "嗯，我在窗边看雨，泡了一壶茶。"))
        }
    }

    @Test
    fun largeModelsSurviveThreeRounds() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = File(context.filesDir, "hotfix-0.5.1-large-models.txt")
        val store = AndroidModelStore(context)
        val registered = store.registry.list()
        val large = registered.filter { it.size > fourGiB }.sortedBy { it.size }
        val control = registered.filter { it.size <= fourGiB }.minByOrNull { it.size }
        assertTrue("调试包里没有登记超过 4 GiB 的模型", large.isNotEmpty())
        val models: List<LocalModelRecord> = listOfNotNull(control) + large
        val engine = LocalModelEngine.shared
        val lines = mutableListOf("设备：${android.os.Build.MODEL}；默认设置 ${EngineOptions()}；开始 ${memory()}")
        report.writeText(lines.joinToString("\n") + "\n")
        for ((index, model) in models.withIndex()) {
            val label = "模型${index + 1}（${String.format(Locale.ROOT, "%.2f", model.size / 1e9)} GB，${if (model.size > fourGiB) "超过 4 GiB，不重排" else "对照，照旧重排"}）"
            val started = SystemClock.elapsedRealtime()
            try {
                engine.load(model.info, EngineOptions()) { store.open(model) }
                lines += "$label 加载 ${SystemClock.elapsedRealtime() - started} ms；${memory()}"
                report.writeText(lines.joinToString("\n") + "\n")
                for (round in 1..3) {
                    withTimeout(300_000) {
                        engine.generate(
                            conversation(round),
                            GenerationParameters(maxTokens = 64, temperature = 0f, seed = 42, cacheKey = "hotfix-0.5.1-$index"),
                        ).toList()
                    }
                    val stats = checkNotNull(engine.lastStats)
                    lines += "$label 第${round}轮：提示 ${stats.promptTokens}（实读 ${stats.readPromptTokens}）用 ${stats.promptMillis} ms；" +
                        "生成 ${stats.generatedTokens} 个，${String.format(Locale.ROOT, "%.2f", stats.tokensPerSecond)} token/秒；${memory()}"
                    report.writeText(lines.joinToString("\n") + "\n")
                    assertTrue(stats.generatedTokens > 0)
                    assertFalse(stats.stopped)
                }
            } finally {
                engine.unload()
            }
        }
        lines += "全部通过；结束 ${memory()}"
        report.writeText(lines.joinToString("\n") + "\n")
    }
}
