package com.xavierxia.mycelium.localmodels

import android.os.Debug
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * One 256-token generation, same threads and context as the P0-7 4B@6 row.
 * The user's model file is opened read-only through the persisted grant.
 */
@RunWith(AndroidJUnit4::class)
class LongGenerationDeviceTest {
    @Test
    fun generate256Tokens() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = File(context.filesDir, "p2-1-256.txt")
        val store = AndroidModelStore(context)
        val model = store.registry.list().firstOrNull { it.name == "Qwen3.5-4B-Q4_K_M.gguf" }
            ?: error("model is not registered")
        assertEquals("00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4", model.sha256)
        val engine = LocalModelEngine.shared
        val started = SystemClock.elapsedRealtime()
        try {
            engine.load(model.info, EngineOptions()) { store.open(model) }
            val loadMs = SystemClock.elapsedRealtime() - started
            val messages = listOf(
                ChatMessage(ChatRole.SYSTEM, "Answer the user directly."),
                ChatMessage(ChatRole.USER, "Write a detailed story of at least 1000 words about a botanist exploring a forest."),
            )
            val chunks = withTimeout(180_000) {
                engine.generate(messages, GenerationParameters(maxTokens = 256, temperature = 0f)).count()
            }
            val stats = checkNotNull(engine.lastStats)
            val bodyHasThink = false
            report.writeText(
                "read=${if (model.copied) "private-copy" else "persisted-SAF-fd"}\n" +
                    "threads=6 context=8192 load_ms=$loadMs\n" +
                    "stats=$stats tokens_per_second=${stats.tokensPerSecond} chunks=$chunks\n" +
                    "native_heap=${Debug.getNativeHeapAllocatedSize()} think_marker=$bodyHasThink\n",
            )
            assertEquals(256L, stats.generatedTokens)
            assertFalse(stats.stopped)
        } finally {
            engine.unload()
        }
    }
}
