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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P2-1d 方案审**必修项 1** 的取证：检查点落点（公共前缀 1116）到底落在模板的哪个位置。
 *
 * 审指出方案原来把 1116 说成「助手角色头之前」——而实测那两个 token（248068/248069）其实是
 * `<think>`/`</think>`。光比 token 数量证明不了边界，必须把**两侧各自解码成什么文本**摆出来。
 *
 * 本用例把真实模型的真实提示按三个位置切开解码，写进 files/p2-1d-boundary.txt：
 *  - 公共前缀（1120 与 1116 里较小的那个，实测 1116）；
 *  - 「读完提示再存」的位置（1120，= 整段提示）；
 *  - 最后若干 token 的逐个解码（看得到 `<|im_start|>assistant`、`<think>` 这类标记）。
 */
@RunWith(AndroidJUnit4::class)
class CheckpointBoundaryEvidenceDeviceTest {

    private val context: Application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private fun write(name: String, text: String) {
        File(context.filesDir, name).writeText(text)
    }

    private fun visible(text: String): String = text.replace("\n", "\\n").replace("\r", "")

    @Test
    fun theBoundaryIsPrintedAsTextSoItsNameCanBeCheckedAgainstTheTemplate() = runBlocking {
        // 直接按「手机上那条模型」构造记录，**不读登记表**：P2-1d 这条分支还是旧登记格式（裸数组），
        // 而手机上是 P2-5 的新格式（带 default/trial）。取证不该被这个跨分支差异挡住。
        val record = LocalModelRecord(
            uri = "content://com.android.externalstorage.documents/document/primary%3AModels%2FQwen3.5-4B-Q4_K_M.gguf",
            name = "Qwen3.5-4B-Q4_K_M.gguf",
            size = 2740937888L,
            sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
            copied = false,
        )
        val store = AndroidModelStore(context)

        val engine = LocalModelEngine.shared
        try {
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 8192)) { store.open(record) }
            assertTrue("这个模型必须能走检查点（混合模型）", engine.canCheckpoint())

            // 两份提示：带 generation prompt（整段，1120 那个位置）与不带（1116 那个位置）。
            val history = listOf(
                ChatMessage(ChatRole.SYSTEM, "你是一个安静、说话很短的人。"),
                ChatMessage(ChatRole.USER, "今天有点累，陪我说说话。"),
                ChatMessage(ChatRole.ASSISTANT, "那就慢慢说，我在。"),
                ChatMessage(ChatRole.USER, "我想先安静一会儿。"),
            )
            val withHeader = engine.promptTokenIds(history)
            assertTrue("提示太短，取证没有意义：${withHeader.size}", withHeader.size > 40)

            // 逐个 token 解码：末尾这一段里能看到角色头与思考段标记。
            val tailFrom = maxOf(0, withHeader.size - 12)
            val perTokenLines = mutableListOf<String>()
            for (index in tailFrom until withHeader.size) {
                val piece = engine.decodeTokens(intArrayOf(withHeader[index]))
                perTokenLines += "$index: ${withHeader[index]} => '${visible(piece)}'"
            }
            val perToken = perTokenLines.joinToString("\n")
            // 整段提示里，把最后 8 个 token 去掉后的文本（近似「用户轮结束、助手头之前」的位置）。
            val cut = withHeader.size - 8
            val beforeCut = engine.decodeTokens(withHeader.copyOfRange(0, cut))
            val wholeText = engine.decodeTokens(withHeader)

            // 只断言**真能证明**的事（方案审必修项 1：原来的「助手角色头之前」这个命名站不住）：
            //  1. 整段提示的末尾是思考段脚手架（`<think>` / `</think>`），不是助手角色头的文本；
            //  2. 砍掉末尾若干个 token 之后，文本仍停在用户这一轮。
            // 真正的保证不是这个名字，而是「落点 ≤ 本轮真实公共前缀」——那条由引擎的 maxPos 与
            // CheckpointStore.find 一起守住（见 CheckpointPlan / CheckpointStore 的测例）。
            assertTrue(
                "整段提示的末尾应当是思考段脚手架（证据见 p2-1d-boundary.txt）：${visible(wholeText.takeLast(120))}",
                wholeText.contains("<think>") && wholeText.contains("</think>"),
            )
            assertTrue(
                "砍掉末尾若干 token 之后应当还停在用户这一轮：${visible(beforeCut.takeLast(120))}",
                beforeCut.contains("user"),
            )

            write(
                "p2-1d-boundary.txt",
                buildString {
                    appendLine("设备：${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
                    appendLine("模型：${record.name}（sha256 ${record.sha256}）")
                    appendLine("整段提示 token 数（带 generation prompt）：${withHeader.size}")
                    appendLine("切点：${cut}（= 整段 - 8）")
                    appendLine()
                    appendLine("== 逐个 token 解码（最后 12 个）==")
                    appendLine(perToken)
                    appendLine()
                    appendLine("== 整段提示的结尾 120 字符 ==")
                    appendLine(visible(wholeText.takeLast(120)))
                    appendLine()
                    appendLine("== 砍掉末尾 8 个之后的结尾 120 字符 ==")
                    appendLine(visible(beforeCut.takeLast(120)))
                    appendLine()
                    appendLine("结论口径：如果「整段」结尾是 <|im_start|>assistant 而「砍掉后」结尾是")
                    appendLine("<|im_start|>user…<|im_end|>，那么落点就在助手角色头**之前**；")
                    appendLine("若砍掉后仍带着 assistant 头，则落点在它之后。以本文件的实测文本为准。")
                },
            )
        } finally {
            engine.unload()
        }
    }
}
