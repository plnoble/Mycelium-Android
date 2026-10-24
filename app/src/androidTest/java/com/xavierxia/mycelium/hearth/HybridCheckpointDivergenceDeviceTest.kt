package com.xavierxia.mycelium.hearth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xavierxia.mycelium.llm.AndroidModelStore
import com.xavierxia.mycelium.llm.EngineOptions
import com.xavierxia.mycelium.llm.LocalModelEngine
import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.ChatRole
import com.xavierxia.mycelium.model.GenerationParameters
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P2-1d 第 1 步的实测：用**真实的 Qwen3.5 模板**，把连续两轮重新拼出来的提示编成 token，比较它们的
 * 最长公共前缀，量出「分叉点落在哪里」。
 *
 * 为什么必须先量：检查点只能存在「位置不超过公共前缀」的地方。聊天模板会把上一轮回答里的思考段去掉、
 * 再补上新的角色标记，所以两轮的分叉点通常不在「上一轮回答的开头」，而在更靠后的位置。凭印象定位置，
 * 检查点就会恢复出位置不对的循环层状态，模型答非所问。
 *
 * 两个位置候选各算一次距离：
 *  - A：这一轮「用户开始说话」之前（server 的做法，参考 server-context.cpp）；
 *  - B：这一轮读完提示、开始生成之前（也就是上一轮提示的末尾）。
 *
 * 结果写 `files/p2-1d-divergence.txt`。
 */
@RunWith(AndroidJUnit4::class)
class HybridCheckpointDivergenceDeviceTest {

    private suspend fun model(context: android.content.Context) = AndroidModelStore(context).registry.list()
        .firstOrNull { it.name == "Qwen3.5-4B-Q4_K_M.gguf" }
        ?: error("Qwen3.5-4B 还没有登记")

    @Test
    fun twoConsecutiveRoundsShareAPrefixThatEndsNearThePreviousAnswer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidModelStore(context)
        val model = model(context)
        val engine = LocalModelEngine.shared
        val report = StringBuilder()
        try {
            engine.load(model.info, EngineOptions()) { store.open(model) }
            val character = characterFor(model.sha256, model.name)
            val history = historyOf(character, targetTokens = 1200)

            // 第一轮：历史 + 问题一。这是「上一轮读完提示、开始生成之前」的位置（候选 B）。
            val question1 = "第 1 问：帮我把这件事理一理，先说结论。"
            val round1 = assemblePrompt(character, history, question1, 8192 - 256).messages
            val t1 = engine.promptTokenIds(round1)

            // 真的跑一轮，拿到真实回答（温度 0，方便复现）。
            val answer1 = withTimeout(300_000) {
                engine.generate(round1, GenerationParameters(maxTokens = 256, temperature = 0f))
                    .toList().joinToString("")
            }

            // 第二轮：历史 + 问题一 + 第一轮的真实回答 + 问题二。
            val question2 = "第 2 问：那就从最小的一步开始，具体怎么开头？"
            val conversation = history.toMutableList()
            conversation += ChatMessage(ChatRole.USER, question1)
            conversation += ChatMessage(ChatRole.ASSISTANT, answer1)
            val round2 = assemblePrompt(character, conversation, question2, 8192 - 256).messages
            val t2 = engine.promptTokenIds(round2)

            // 最长公共前缀（和原生 shared_prefix 同一判据）。
            val limit = minOf(t1.size, t2.size)
            var shared = 0
            while (shared < limit && t1[shared] == t2[shared]) shared++

            // 候选 A：第二轮里「用户第二个问题」这个 role 标记之前的最后一个位置。找不到就记 -1。
            val userMarker = t2.size - 1 // 第二个问题在提示结尾，下面按内容反推
            val answerTokenApprox = t1.size // 上一轮提示的末尾就是候选 B

            report.append("model=${model.name}\n")
            report.append("round1_tokens=${t1.size} round2_tokens=${t2.size}\n")
            report.append("common_prefix=$shared\n")
            report.append("divergence_at=$shared\n")
            report.append("round1_suffix_from_divergence=${t1.size - shared} tokens\n")
            report.append("round2_suffix_from_divergence=${t2.size - shared} tokens\n")
            report.append("candidate_B_round1_end=$answerTokenApprox 与公共前缀的距离=${answerTokenApprox - shared}\n")
            report.append("candidate_A_round2_last=${userMarker} 与公共前缀的距离=${userMarker - shared}\n")
            report.append("answer1_chars=${answer1.length}\n")
            // 分叉点附近两边的 token id，便于判断是不是角色标记/模板差异。
            val from = (shared - 3).coerceAtLeast(0)
            report.append("prefix_tail_round1=${t1.toList().subList(from, minOf(t1.size, shared + 3))}\n")
            report.append("prefix_tail_round2=${t2.toList().subList(from, minOf(t2.size, shared + 3))}\n")
            report.append("answer1_head=${answer1.take(60).replace("\n", "\\n")}\n")

            File(context.filesDir, "p2-1d-divergence.txt").writeText(report.toString())
            println(report.toString())
        } finally {
            File(context.filesDir, "p2-1d-divergence.txt").writeText(report.toString())
            engine.unload()
        }
    }

    private fun characterFor(modelId: String, modelName: String) = HearthCharacter(
        id = "p2-1d-probe",
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
        temperature = 0f,
        maxTokens = 256,
        color = 0,
        updatedAt = "2026-10-22T00:00",
    )

    private fun historyOf(character: HearthCharacter, targetTokens: Int): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        var index = 1
        while (messages.sumOf { estimateTokens(it.content) } < targetTokens) {
            messages += ChatMessage(
                ChatRole.USER,
                "第 $index 件事：手上有三个活儿堆着，哪个都开了个头，晚上又睡不好，白天没精神，越拖越多。你说我该先从哪儿下手？$index",
            )
            messages += ChatMessage(
                ChatRole.ASSISTANT,
                "先说结论：挑一个今天能收尾的先干掉，别同时开三个。第 $index 件事你先把最小的一步写下来，做完再动第二个。",
            )
            index++
        }
        return messages
    }
}
