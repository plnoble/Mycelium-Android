package com.xavierxia.mycelium.hearth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xavierxia.mycelium.llm.AndroidModelStore
import com.xavierxia.mycelium.llm.EngineOptions
import com.xavierxia.mycelium.llm.LocalModelEngine
import com.xavierxia.mycelium.llm.LocalModelRecord
import com.xavierxia.mycelium.llm.NativeArchVariant
import com.xavierxia.mycelium.llm.NativeLibraryVariant
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
 * P2-1d 真机验收（2026-10-23 夜）：**默认路径必须挑中快变体**。
 *
 * 这一条只证明「这台机器上挑了快的那份、而且能生成」。基线那一份由
 * [NativeVariantBaselineDeviceTest] 在**另一个进程**里验（同一个进程只能加载一份库）。
 */
@RunWith(AndroidJUnit4::class)
class NativeVariantFastDeviceTest {

    private val record = LocalModelRecord(
        uri = "content://com.android.externalstorage.documents/document/primary%3AModels%2FQwen3.5-4B-Q4_K_M.gguf",
        name = "Qwen3.5-4B-Q4_K_M.gguf",
        size = 2740937888L,
        sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
        copied = false,
    )

    @Test
    fun theDefaultPathLoadsTheFastVariantAndGenerates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val loaded = NativeLibraryVariant.loaded.ifEmpty { NativeLibraryVariant.load() }
        val variant = NativeArchVariant.current()

        val engine = LocalModelEngine.shared
        val store = AndroidModelStore(context)
        engine.load(record.info, EngineOptions(threads = 8, contextLength = 4096)) { store.open(record) }
        val text = engine.generate(
            listOf(
                ChatMessage(ChatRole.SYSTEM, "你是一个安静、说话很短的人。"),
                ChatMessage(ChatRole.USER, "今天有点累"),
            ),
            GenerationParameters(maxTokens = 12, temperature = 0f, seed = 42),
        ).toList().joinToString("")
        engine.unload()

        File(context.filesDir, "p2-1d-variant-fast.txt").writeText(
            buildString {
                appendLine("设备：${android.os.Build.MODEL} / ${android.os.Build.SOC_MODEL}")
                appendLine("cpu features: ${NativeLibraryVariant.readCpuFeatures().take(160)}")
                appendLine("加载的库: $loaded")
                appendLine("编译变体: $variant")
                appendLine("正文: $text")
            },
        )

        assertTrue("这台机器有 dotprod+i8mm，必须挑快变体（实际 $loaded）", loaded == NativeLibraryVariant.FAST)
        assertTrue("快变体应当带 i8mm（实际 $variant）", variant.contains("i8mm"))
        assertTrue("快变体必须能生成", text.isNotBlank())
    }
}