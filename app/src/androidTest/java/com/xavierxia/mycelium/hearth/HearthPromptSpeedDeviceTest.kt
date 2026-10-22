package com.xavierxia.mycelium.hearth

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xavierxia.mycelium.llm.AndroidModelStore
import com.xavierxia.mycelium.llm.EngineOptions
import com.xavierxia.mycelium.llm.LocalModelEngine
import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.ChatRole
import com.xavierxia.mycelium.model.GenerationParameters
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P2-1c 的真机部分。用真实提示装配与用户登记的那个模型，量三件事：
 *  1. 同一段约 1500 token 的对话连问 3 轮，每轮记**复用**、**实读**、读提示耗时与生成耗时。
 *     目标模型是混合状态模型，预期每轮整段重读（reused == 0）；这里只记读数，不把「必须复用」当失败。
 *  2. 温度 0 时，「有缓存」与「清空缓存」两条路径的输出逐字相同；
 *  3. 每个文件都写进应用私有目录，供交付报告引用。
 *
 * 用手机前先问用户；这个类只在调试包里跑（`adb install -r` + `am instrument`）。
 */
@RunWith(AndroidJUnit4::class)
class HearthPromptSpeedDeviceTest {

    private suspend fun model(context: android.content.Context) = AndroidModelStore(context).registry.list()
        .firstOrNull { it.name == "Qwen3.5-4B-Q4_K_M.gguf" }
        ?: error("Qwen3.5-4B 还没有登记")

    @Test
    fun threeRoundsOfARealConversationReportTheirReuse() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidModelStore(context)
        val model = model(context)
        val character = characterFor(model.sha256, model.name)
        val history = historyOf(character, targetTokens = 1400)
        val engine = LocalModelEngine.shared
        val report = StringBuilder()
        report.append("model=${model.name} sha256=${model.sha256} read=${if (model.copied) "private-copy" else "persisted-SAF-fd"}\n")
        try {
            val loadStarted = SystemClock.elapsedRealtime()
            engine.load(model.info, EngineOptions()) { store.open(model) }
            report.append("load_ms=${SystemClock.elapsedRealtime() - loadStarted}\n")

            // 一段真实的多轮对话：每轮把上一轮的问与答接上去，键保持不变（同一段对话）。
            val conversation = history.toMutableList()
            val key = "device-speed|${character.id}|immersive"
            repeat(3) { round ->
                val question = "第 ${round + 1} 问：帮我把这件事理一理，先说结论。"
                val window = assemblePrompt(character, conversation, question, 8192 - 256)
                val promptTokens = window.messages.sumOf { estimateTokens(it.content) }
                val started = SystemClock.elapsedRealtime()
                val answer = withTimeout(300_000) {
                    engine.generate(window.messages, GenerationParameters(maxTokens = 256, temperature = 0f, cacheKey = key))
                        .toList().joinToString("")
                }
                val wall = SystemClock.elapsedRealtime() - started
                val stats = checkNotNull(engine.lastStats)
                report.append(
                    "round=${round + 1} estimated_prompt_tokens=$promptTokens prompt_tokens=${stats.promptTokens} " +
                        "reused_tokens=${stats.reusedPromptTokens} read_tokens=${stats.readPromptTokens} " +
                        "prompt_ms=${stats.promptMillis} generated_tokens=${stats.generatedTokens} " +
                        "generation_ms=${stats.generationMillis} tokens_per_second=${stats.tokensPerSecond} " +
                        "hidden_tokens=${stats.hiddenTokens} hidden_ms=${stats.hiddenMillis} " +
                        "reuse_fallback_reason=${stats.reuseFallbackReason} wall_ms=$wall answer_chars=${answer.length} " +
                        "stopped=${stats.stopped}\n",
                )
                conversation += ChatMessage(ChatRole.USER, question)
                conversation += ChatMessage(ChatRole.ASSISTANT, answer)
            }

            // 目标模型是混合状态模型，统筹裁定的预期是每轮整段重读（reused == 0、reason == 3）。
            // 这里只记录读数，不把「必须复用」当成失败：复用与否由交付报告交给统筹裁定。
            val rounds = report.toString().lines().filter { it.startsWith("round=") }
            assertEquals("应当跑满三轮", 3, rounds.size)
        } finally {
            File(context.filesDir, "p2-1c-speed.txt").writeText(report.toString())
            engine.unload()
        }
    }

    @Test
    fun reuseAndNoReuseProduceTheSameAnswerAtTemperatureZero() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidModelStore(context)
        val model = model(context)
        val character = characterFor(model.sha256, model.name)
        val conversation = historyOf(character, targetTokens = 600)
        val question = "温度 0 对比：先把结论说出来。"
        val window = assemblePrompt(character, conversation, question, 8192 - 256)
        val engine = LocalModelEngine.shared
        val report = StringBuilder()
        val key = "device-zero|${character.id}"
        try {
            engine.load(model.info, EngineOptions()) { store.open(model) }

            // 第一遍：整段读，把提示留在 KV 里。
            val first = withTimeout(300_000) {
                engine.generate(window.messages, GenerationParameters(maxTokens = 128, temperature = 0f, cacheKey = key))
                    .toList().joinToString("")
            }
            val firstStats = checkNotNull(engine.lastStats)

            // 第二遍：同一段提示、同一个键 —— 应当复用，输出必须与第一遍逐字相同。
            val reused = withTimeout(300_000) {
                engine.generate(window.messages, GenerationParameters(maxTokens = 128, temperature = 0f, cacheKey = key))
                    .toList().joinToString("")
            }
            val reusedStats = checkNotNull(engine.lastStats)

            // 第三遍：不传键 —— 引擎清空 KV、整段重读，输出仍必须逐字相同。
            val cleared = withTimeout(300_000) {
                engine.generate(window.messages, GenerationParameters(maxTokens = 128, temperature = 0f))
                    .toList().joinToString("")
            }
            val clearedStats = checkNotNull(engine.lastStats)

            report.append("key=$key\n")
            listOf("first" to firstStats, "reused" to reusedStats, "cleared" to clearedStats).forEach { (what, stats) ->
                report.append(
                    "$what prompt_tokens=${stats.promptTokens} reused_tokens=${stats.reusedPromptTokens} " +
                        "read_tokens=${stats.readPromptTokens} prompt_ms=${stats.promptMillis} " +
                        "generated_tokens=${stats.generatedTokens} generation_ms=${stats.generationMillis} " +
                        "reuse_fallback_reason=${stats.reuseFallbackReason}\n",
                )
            }
            report.append("first_answer=$first\nreused_answer=$reused\ncleared_answer=$cleared\n")

            // 混合模型预期不复用（reused == 0）；真复用了就必须与整段重读逐字相同，否则立刻报统筹。
            if (reusedStats.reusedPromptTokens > 0) {
                assertEquals("温度 0：复用了提示，但答案与整段重读的不一样", first, reused)
            }
            assertEquals("温度 0：清空缓存重读的输出也必须逐字相同", first, cleared)
        } finally {
            File(context.filesDir, "p2-1c-zero.txt").writeText(report.toString())
            engine.unload()
        }
    }

    /**
     * 隐藏段的三个数（P2-1c）：默认关思考与打开思考各跑一轮，记隐藏 token 数、隐藏段耗时，
     * 以及隐藏段结束后正文还剩多少预算（上下文减去已读提示再减去隐藏段）。
     */
    @Test
    fun hiddenSpanNumbersWithThinkingOnAndOff() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidModelStore(context)
        val model = model(context)
        val character = characterFor(model.sha256, model.name)
        val conversation = historyOf(character, targetTokens = 400)
        val question = "用两三句话说说你怎么看这件事。"
        val window = assemblePrompt(character, conversation, question, 8192 - 256)
        val engine = LocalModelEngine.shared
        val report = StringBuilder()
        try {
            engine.load(model.info, EngineOptions()) { store.open(model) }
            listOf(false, true).forEach { thinking ->
                engine.setThinking(thinking)
                val answer = withTimeout(300_000) {
                    engine.generate(window.messages, GenerationParameters(maxTokens = 256, temperature = 0f))
                        .toList().joinToString("")
                }
                val stats = checkNotNull(engine.lastStats)
                val bodyBudgetLeft = 8192 - stats.promptTokens - stats.hiddenTokens
                report.append(
                    "thinking=$thinking prompt_tokens=${stats.promptTokens} hidden_tokens=${stats.hiddenTokens} " +
                        "hidden_ms=${stats.hiddenMillis} generated_tokens=${stats.generatedTokens} " +
                        "body_budget_left=$bodyBudgetLeft answer_chars=${answer.length}\n",
                )
            }
        } finally {
            engine.setThinking(false)
            File(context.filesDir, "p2-1c-hidden.txt").writeText(report.toString())
            engine.unload()
        }
    }

    private fun characterFor(modelId: String, modelName: String) = HearthCharacter(
        id = "speed-probe",
        name = "老周",
        avatarChar = "周",
        personality = "话少、稳。先问清楚情况再给建议，不灌鸡汤，不做人身攻击。",
        speech = "短句、口语，条理清楚；常用「先说结论」。",
        background = "认识多年的同事，工作上遇到事可以找他理一理。",
        relationship = "老朋友",
        greeting = "有事说事，别绕。",
        modelKind = ModelKind.LOCAL,
        modelId = modelId,
        modelName = modelName,
        serviceId = "",
        serviceName = "",
        cloudConfirmedTarget = "",
        temperature = 0.7f,
        maxTokens = 256,
        color = 0,
        updatedAt = "2026-10-18T00:00",
    )

    /** Filler turns, so the window really carries a long history and not just the sheet. */
    private fun historyOf(character: HearthCharacter, targetTokens: Int): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        var index = 1
        while (messages.sumOf { estimateTokens(it.content) } < targetTokens) {
            messages += ChatMessage(
                ChatRole.USER,
                "第 $index 件事：手上有三个活儿堆着，哪个都开了个头，晚上又睡不好，" +
                    "白天没精神，越拖越多。你说我该先从哪儿下手？$index",
            )
            messages += ChatMessage(
                ChatRole.ASSISTANT,
                "先说结论：挑一个今天能收尾的先干掉，别同时开三个。第 $index 件事你先把最小的一步写下来，" +
                    "做完再动第二个。",
            )
            index++
        }
        return messages
    }
}