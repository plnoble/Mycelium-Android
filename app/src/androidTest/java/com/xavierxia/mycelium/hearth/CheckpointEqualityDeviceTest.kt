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
 * P2-1d 的 S5 生死线：**温度 0 下，用检查点与整段重读必须逐字相同**。
 *
 * 做法：两次都从「新加载模型」开始，跑同样的第 1 轮（建立缓存/检查点），再跑同样的第 2 轮：
 *  - A 组：检查点开关**开**（第 2 轮走恢复）；
 *  - B 组：开关**关**（第 2 轮走 P2-1c 的整段重读）。
 * 温度 0 + 同一 seed ⇒ 贪心解码，两条路的输出必须一比一相同。**不相同就说明恢复出来的状态不对**，
 * 这一包不能交付（方案与任务书都把这句写死了）。
 *
 * 同时记 3 轮读数（提示 token 数、复用数、实读数、读提示耗时）与检查点占用，给交付报告用。
 */
@RunWith(AndroidJUnit4::class)
class CheckpointEqualityDeviceTest {

    private val context: Application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private val record = LocalModelRecord(
        uri = "content://com.android.externalstorage.documents/document/primary%3AModels%2FQwen3.5-4B-Q4_K_M.gguf",
        name = "Qwen3.5-4B-Q4_K_M.gguf",
        size = 2740937888L,
        sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
        copied = false,
    )

    /** 三轮对话：第 2、3 轮才会用得上检查点。 */
    private fun history(turn: Int): List<ChatMessage> = buildList {
        add(ChatMessage(ChatRole.SYSTEM, "你是一个安静、说话很短的人，每次只说一句话。"))
        add(ChatMessage(ChatRole.USER, "今天有点累，陪我说说话。"))
        add(ChatMessage(ChatRole.ASSISTANT, "那就慢慢说，我在。"))
        add(ChatMessage(ChatRole.USER, "用一句话说说你现在的心情。"))
        if (turn >= 2) add(ChatMessage(ChatRole.ASSISTANT, "我在这盏灯下坐着，风从窗口进来。"))
        if (turn >= 2) add(ChatMessage(ChatRole.USER, "那你现在想做什么？"))
        if (turn >= 3) add(ChatMessage(ChatRole.ASSISTANT, "想把今天的事都放下。"))
        if (turn >= 3) add(ChatMessage(ChatRole.USER, "再说一句就好。"))
    }

    /**
     * 跑一轮，返回（正文, 读数一行）。温度 0、固定 seed。
     *
     * **落点必须由调用方算出来**（`GenerationParameters.checkpointPos`）：引擎只在 >0 时才存检查点。
     * 这里按真机实测的脚手架长度算——整段提示末尾的「生成脚手架」（助手头 + `<think></think>`）是 4 个
     * token（见 p2-1d-boundary.txt：1120 → 1116）。应用侧将来用「带/不带 add_generation_prompt 两次
     * tokenize 的长度差」算同一个数，不写死。
     */
    private suspend fun round(engine: LocalModelEngine, turn: Int): Pair<String, String> {
        val whole = engine.promptTokenIds(history(turn))
        // 落点必须用**应用侧同一个算法**（`tokenizeBoundary`：带/不带 generation prompt 两次 tokenize
        // 的长度差），不能写死常数。2026-10-23 夜的真机事故就是这条：这里原本写死「末尾 4 个 token
        // 是脚手架」，而这套模板实测是 7 个（tokenizeBoundary = [27, 20]），于是落点落在脚手架中间，
        // 快照把「之后会被回答替换掉」的几个 token 也带了进去，循环层状态因此与真算出来的不一致——
        // 温度 0 逐字对照稳定给出不同正文。
        // 落点算不出来就**直接失败**：回退成 `whole.size - 1` 会把落点放进脚手架里，等于用错的
        // 条件做生死线验收（全量审 R2 P2-4）。
        val computed = engine.checkpointPositionFor(history(turn))
        assertTrue("第 $turn 轮落点计算失败（tokenizeBoundary 没给出边界）", computed > 0 && computed < whole.size)
        val checkpointPos = computed
        val text = engine.generate(
            history(turn),
            GenerationParameters(
                maxTokens = 24, temperature = 0f, seed = 42,
                cacheKey = "equality-测试会话", checkpointPos = checkpointPos,
            ),
        ).toList().joinToString("")
        val stats = engine.lastStats
        val line = "第 $turn 轮：prompt=${stats?.promptTokens} reused=${stats?.reusedPromptTokens} " +
            "read=${stats?.readPromptTokens} prompt_ms=${stats?.promptMillis} " +
            "gen=${stats?.generatedTokens} reason=${stats?.reuseFallbackReason} pos=$checkpointPos " +
            "saved=${stats?.checkpointPos}/${stats?.checkpointBytes}"
        return text to line
    }

    private companion object {
        /** 整段提示末尾「生成脚手架」的 token 数（真机实测 4，见边界取证）。 */
        const val TAIL_SCAFFOLD_TOKENS = 4
    }

    @Test
    fun temperatureZeroOutputIsByteIdenticalWithAndWithoutCheckpoints() = runBlocking {
        val store = AndroidModelStore(context)
        val engine = LocalModelEngine.shared
        val log = mutableListOf<String>()
        try {
            // ---- A 组：检查点开 -------------------------------------------------------------
            engine.checkpointEnabled = true
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 8192)) { store.open(record) }
            val (a1, l1) = round(engine, 1)
            val a1Restored = engine.checkpointRestoredFrom
            val (a2, l2) = round(engine, 2)
            val a2Restored = engine.checkpointRestoredFrom
            val a2Stats = engine.lastStats
            val usage = engine.checkpointUsage()
            log += "[A 开] $l1"
            log += "[A 开] $l2"
            log += "[A 开] 检查点占用：${usage.first} 份 / ${usage.second} 字节"
            log += "[A 开] 第 2 轮恢复位置：${engine.checkpointRestoredFrom}"
            log += "[A 开] 第 2 轮正文：$a1 -> $a2"
            // 全量审 R2 必修项 7：这一条**必须真的恢复成功**，不许靠「恢复失败 + 原因 6」蒙混过去。
            // 整份状态实现下恢复是能成的（第 1 轮存 56 → 第 2 轮从 56 续读），所以这里直接要求
            // restoredFrom > 0；失败就说明这条生死线其实没被测到，用例必须红。
            assertTrue(
                "开着检查点时第 2 轮必须**真的恢复成功**（不是在兜底路径上跑通）。$l2；" +
                    "第 1 轮后仓库=${usage.first} 份/${usage.second} 字节；take=${engine.lastTakeDiagnosis}；" +
                    "本侧原因=${engine.checkpointFallbackReason} restoredFrom=${engine.checkpointRestoredFrom} " +
                    "restoreStep=${engine.lastRestoreStep()}",
                engine.checkpointRestoredFrom > 0,
            )
            assertEquals("恢复失败原因不该出现（恢复是成功的）", 0L, engine.checkpointFallbackReason)

            // ---- B 组：检查点关（重新加载，状态从头开始）--------------------------------------
            engine.unload()
            engine.checkpointEnabled = false
            // 归因实验（2026-10-24，两轮真机）最终结论：
            //  1. 落点必须用模板真实边界（tokenizeBoundary，本段是 53）。此前写死 whole-4=56，
            //     快照把 3 个下一轮会被回答替换掉的 token 也包了进去，循环层状态不一致。
            //  2. 批次形状差异（全量审 R2 P1-1 已修）：断批点改为「每条消息结束处」的集合，跨轮稳定，
            //     所以这里**用生产默认批次**验收——两组只差检查点开关，不再手工改批次（R2 P2-4）。
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 8192)) { store.open(record) }
            val (b1, m1) = round(engine, 1)
            val b1Stats = engine.lastStats
            val (b2, m2) = round(engine, 2)
            val b2Stats = engine.lastStats
            val (b3, m3) = round(engine, 3)
            val b3Stats = engine.lastStats
            log += "[B 关] $m1"
            log += "[B 关] $m2"
            log += "[B 关] $m3"
            log += "[B 关] 第 2/3 轮正文：$b1 -> $b2 -> $b3"

            // 先把证据落盘再断言：断言失败时不能留下上一次运行的旧文件（这一夜就被它骗过一次）。
            File(context.filesDir, "p2-1d-s5.txt").writeText("占位：见下方重写\n")

            // ---- 生死线：逐字相同 -----------------------------------------------------------
            assertEquals("第 1 轮的正文必须一模一样（同一份提示、温度 0）", a1, b1)
            assertEquals("第 2 轮：用检查点与整段重读必须逐字相同（生死线）", a2, b2)
            assertTrue("正文不能是空的（空对空也算「相同」，那是假通过）", a2.isNotBlank())
            // 全量审 R2 P2-4：逐轮验证「真恢复、真全读」——否则逐字相同可能只是两条路都在整段重读。
            assertTrue("第 1 轮不该有恢复（restoredFrom=$a1Restored）", a1Restored == -1)
            assertTrue("第 2 轮必须真恢复（restoredFrom=$a2Restored）", a2Restored > 0)
            assertEquals("第 2 轮复用的 token 数必须等于恢复位置", a2Restored.toLong(), a2Stats?.reusedPromptTokens)
            assertTrue("第 2 轮正文不能为空", a1.isNotBlank() && b1.isNotBlank())
            assertEquals("关闭组第 2 轮必须整段读", 0L, b2Stats?.reusedPromptTokens)
            assertEquals("关闭组第 2 轮读的 token 数必须等于提示长度", b2Stats?.promptTokens, b2Stats?.readPromptTokens)
            assertEquals("关闭组第 3 轮必须整段读", 0L, b3Stats?.reusedPromptTokens)
            assertTrue("关闭组正文不能为空", b3.isNotBlank())

            NativePromptBatch.set(0)
            NativePromptBatch.set(0)
            // ---- 3 轮读数（交付数据，并补第三轮的开/关对照——R2 必修项 7）--------------------
            engine.unload()
            engine.checkpointEnabled = true
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 8192)) { store.open(record) }
            val a3Texts = mutableListOf<String>()
            // 全量审 P2-9：3 轮组**每一轮**都要断言「真的恢复成功」，不许靠「恢复失败 + 整段重读」
            // 蒙混过逐字比对——那等于这条生死线没被测到。
            val a3Restored = mutableListOf<Int>()
            val a3Reasons = mutableListOf<Long>()
            listOf(1, 2, 3).forEach { turn ->
                val (text, line) = round(engine, turn)
                a3Texts += text
                a3Restored += engine.checkpointRestoredFrom
                a3Reasons += engine.checkpointFallbackReason
                log += "[3 轮] $line"
            }
            // 第三轮也要开/关逐字比对：同一份提示、同一温度 0，两条路必须给出同一段正文。
            assertEquals("第 3 轮：用检查点与整段重读必须逐字相同", a3Texts[2], b3)
            // 第 2、3 轮必须真的从检查点续读（第 1 轮还没有可恢复的，restoredFrom=-1 属正常）。
            assertEquals("3 轮组第 2 轮必须真恢复", true, a3Restored[1] > 0)
            assertEquals("3 轮组第 2 轮不该回退", 0L, a3Reasons[1])
            assertEquals("3 轮组第 3 轮必须真恢复", true, a3Restored[2] > 0)
            assertEquals("3 轮组第 3 轮不该回退", 0L, a3Reasons[2])
            val finalUsage = engine.checkpointUsage()
            log += "[3 轮] 结束时检查点占用：${finalUsage.first} 份 / ${finalUsage.second} 字节"

            File(context.filesDir, "p2-1d-s5.txt").writeText(
                buildString {
                    appendLine("设备：${android.os.Build.MODEL} / ${android.os.Build.SOC_MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
                    appendLine("模型：${record.name}")
                    appendLine("温度：0（贪心）、seed 42、maxTokens 24")
                    appendLine("线程数：8、上下文 8192")
                    appendLine()
                    log.forEach { appendLine(it) }
                },
            )
        } finally {
            engine.unload()
            engine.checkpointEnabled = false
        }
    }
}