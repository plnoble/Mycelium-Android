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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The P2-1 review asked for a realistic hearth prompt — a character sheet plus several turns, about
 * 1500 tokens — three times, recording how long the prompt takes to read and how fast the answer
 * comes. This uses the real prompt assembly and the registry entry the user imported; the model
 * file is opened read-only through its persisted grant.
 */
@RunWith(AndroidJUnit4::class)
class HearthPromptSpeedDeviceTest {
    @Test
    fun threeRunsWithAnAbout1500TokenPrompt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidModelStore(context)
        val model = store.registry.list().firstOrNull { it.name == "Qwen3.5-4B-Q4_K_M.gguf" }
            ?: error("Qwen3.5-4B 还没有登记")
        val character = HearthCharacter(
            id = "speed-probe",
            name = "老周",
            avatarChar = "周",
            personality = "话少、稳。先问清楚情况再给建议，不灌鸡汤，不做人身攻击。",
            speech = "短句、口语，条理清楚；常用「先说结论」。",
            background = "认识多年的同事，工作上遇到事可以找他理一理。",
            relationship = "老朋友",
            greeting = "有事说事，别绕。",
            modelKind = ModelKind.LOCAL,
            modelId = model.sha256,
            modelName = model.name,
            serviceId = "",
            serviceName = "",
            cloudConfirmedService = "",
            temperature = 0.7f,
            maxTokens = 256,
            color = 0,
            updatedAt = "2026-10-18T00:00",
        )
        val history = historyOf(character, targetTokens = 1400)
        val question = "帮我把这件事理一理，先说结论。"
        val window = assemblePrompt(character, history, question, 8192 - 256)
        val promptTokens = window.messages.sumOf { estimateTokens(it.content) }
        assertTrue("提示只有 $promptTokens 个估算 token，太少", promptTokens >= 1200)

        val engine = LocalModelEngine.shared
        val report = StringBuilder()
        report.append("model=${model.name} sha256=${model.sha256} read=${if (model.copied) "private-copy" else "persisted-SAF-fd"}\n")
        report.append("messages=${window.messages.size} estimated_prompt_tokens=$promptTokens\n")
        try {
            val loadStarted = SystemClock.elapsedRealtime()
            engine.load(model.info, EngineOptions()) { store.open(model) }
            report.append("load_ms=${SystemClock.elapsedRealtime() - loadStarted}\n")
            repeat(3) { run ->
                val started = SystemClock.elapsedRealtime()
                val chunks = withTimeout(300_000) {
                    engine.generate(window.messages, GenerationParameters(maxTokens = 256, temperature = 0f)).count()
                }
                val wall = SystemClock.elapsedRealtime() - started
                val stats = checkNotNull(engine.lastStats)
                report.append(
                    "run=${run + 1} prompt_tokens=${stats.promptTokens} prompt_ms=${stats.promptMillis} " +
                        "generated_tokens=${stats.generatedTokens} generation_ms=${stats.generationMillis} " +
                        "tokens_per_second=${stats.tokensPerSecond} wall_ms=$wall chunks=$chunks stopped=${stats.stopped}\n",
                )
            }
        } finally {
            File(context.filesDir, "p2-2-speed.txt").writeText(report.toString())
            engine.unload()
        }
    }

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
