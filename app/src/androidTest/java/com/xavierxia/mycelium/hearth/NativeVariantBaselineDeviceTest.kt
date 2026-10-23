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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P2-1d 真机验收：**强制基线变体**也要能加载、能生成（老手机靠这条保命）。
 *
 * 必须在**独立进程**里跑：一个进程只能加载一份原生库，所以这个类要单独
 * `am instrument -e class ...` 调用。第一句就先钉住基线，绝不能让引擎提前把快变体加载进来。
 */
@RunWith(AndroidJUnit4::class)
class NativeVariantBaselineDeviceTest {

    private val record = LocalModelRecord(
        uri = "content://com.android.externalstorage.documents/document/primary%3AModels%2FQwen3.5-4B-Q4_K_M.gguf",
        name = "Qwen3.5-4B-Q4_K_M.gguf",
        size = 2740937888L,
        sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
        copied = false,
    )

    @Test
    fun theForcedBaselineLoadsAndGenerates() = runBlocking {
        // 第一步就钉基线：任何引擎/桥的类初始化都会调用 load()，那时已经晚了。
        val loaded = NativeLibraryVariant.load(forceBaseline = true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
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

        File(context.filesDir, "p2-1d-variant-baseline.txt").writeText(
            buildString {
                appendLine("设备：${android.os.Build.MODEL} / ${android.os.Build.SOC_MODEL}")
                appendLine("加载的库: $loaded")
                appendLine("编译变体: $variant")
                appendLine("正文: $text")
            },
        )

        assertEquals("强制基线就必须是基线库", NativeLibraryVariant.BASELINE, loaded)
        assertTrue("基线变体不该带 i8mm（实际 $variant）", !variant.contains("i8mm"))
        assertTrue("基线变体必须能生成", text.isNotBlank())
    }
}