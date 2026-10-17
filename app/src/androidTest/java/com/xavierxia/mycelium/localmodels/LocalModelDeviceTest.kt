package com.xavierxia.mycelium.localmodels

import android.content.Intent
import android.os.Debug
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xavierxia.mycelium.MainActivity
import com.xavierxia.mycelium.llm.*
import com.xavierxia.mycelium.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalModelDeviceTest {
    @Test fun loadStreamStopUnloadWithUserSelectedModel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = File(context.filesDir, "p2-1-device.txt")
        report.writeText("P2-1 device evidence\n")
        fun record(line: String) { report.appendText(line + "\n") }
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val store = AndroidModelStore(context)
        val model = store.registry.list().firstOrNull { it.name == "Qwen3.5-4B-Q4_K_M.gguf" }
            ?: error("Import the user's Qwen3.5-4B-Q4_K_M.gguf using the system picker first")
        assertEquals("00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4", model.sha256)
        val engine = LocalModelEngine.shared
        fun rssKb(): Long = File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }
            .split(Regex("\\s+"))[1].toLong()
        val before = rssKb()
        val start = SystemClock.elapsedRealtime()
        try {
            engine.load(model.info, EngineOptions()) { store.open(model) }
            record("read=${if (model.copied) "private-copy" else "persisted-SAF-fd"} load_ms=${SystemClock.elapsedRealtime() - start}")
            val loaded = rssKb()
            record("rss_before_kb=$before rss_loaded_kb=$loaded")
            val messages = listOf(ChatMessage(ChatRole.SYSTEM, "Answer the user directly."),
                ChatMessage(ChatRole.USER, "Write a detailed story of at least 1000 words about a botanist exploring a forest."))
            val parameters = GenerationParameters(maxTokens = 64, temperature = 0f)
            val deltas = withTimeout(180_000) { engine.generate(messages, parameters).toList() }
            val stats = checkNotNull(engine.lastStats)
            record("first_stats=$stats tokens_per_second=${stats.tokensPerSecond} chunks=${deltas.size}")
            assertEquals(64L, stats.generatedTokens)
            assertTrue(deltas.size > 1)
            assertTrue(deltas.none { it.isEmpty() })
            val body = deltas.joinToString("")
            assertFalse(body.contains("<think>"))
            assertFalse(body.contains("</think>"))
            // Deterministic repetition also exercises KV/recurrent-state clearing.
            val repeated = withTimeout(180_000) { engine.generate(messages, parameters).toList().joinToString("") }
            assertEquals("ordered stream reconstruction", body, repeated)
            record("deterministic_stream_order=passed body=$body")

            val firstChunk = CompletableDeferred<Unit>()
            val generating = launch(Dispatchers.Default) {
                engine.generate(messages, GenerationParameters(maxTokens = 512, temperature = 0f)).collect {
                    firstChunk.complete(Unit)
                }
            }
            withTimeout(180_000) { firstChunk.await() }
            val stop = SystemClock.elapsedRealtime()
            engine.stop()
            withTimeout(1000) { generating.join() }
            val stopMs = SystemClock.elapsedRealtime() - stop
            record("stop_during_generation_ms=$stopMs")
            assertTrue(stopMs < 1000)

            val prefill = launch(Dispatchers.Default) {
                engine.generate(listOf(ChatMessage(ChatRole.USER, "Explain: " + "forest ecology ".repeat(1000))),
                    GenerationParameters(maxTokens = 64)).toList()
            }
            delay(500)
            val cancel = SystemClock.elapsedRealtime()
            withTimeout(1000) { prefill.cancelAndJoin() }
            record("cancel_during_prefill_ms=${SystemClock.elapsedRealtime() - cancel}")

            val peak = rssKb()
            engine.unload()
            System.gc()
            delay(500)
            val after = rssKb()
            record("rss_before_unload_kb=$peak rss_after_unload_kb=$after native_heap=${Debug.getNativeHeapAllocatedSize()}")
            assertNull(engine.loadedModel.value)
            assertTrue("RSS must drop by at least 256 MiB", peak - after > 256 * 1024)
            record("RESULT=PASSED")
        } catch (failure: Throwable) {
            record("RESULT=FAILED ${failure.javaClass.simpleName}: ${failure.message}")
            throw failure
        } finally {
            withContext(NonCancellable) { engine.unload() }
            scenario.close()
        }
    }
}

