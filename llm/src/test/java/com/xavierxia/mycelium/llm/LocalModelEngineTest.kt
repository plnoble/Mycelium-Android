package com.xavierxia.mycelium.llm

import com.xavierxia.mycelium.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LocalModelEngineTest {
    private class Descriptor : ModelFile {
        override val fd = 7
        var closed = false
        override fun close() { closed = true }
    }
    private class Bridge : EngineBridge {
        val stopped = AtomicBoolean()
        val started = CountDownLatch(1)
        val exited = CountDownLatch(1)
        var blockingLoad = false
        var blockingGeneration = false
        var active = false
        var unloads = 0
        override fun begin() { stopped.set(false) }
        override fun stop() { stopped.set(true) }
        override fun load(fd: Int, threads: Int, context: Int) {
            assertEquals(6, threads)
            assertEquals(8192, context)
            if (blockingLoad) waitForStop()
        }
        private fun waitForStop() {
            active = true
            started.countDown()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!stopped.get() && System.nanoTime() < deadline) Thread.sleep(1)
                check(stopped.get()) { "stop signal did not reach native work" }
            } finally { active = false; exited.countDown() }
        }
        override fun unload() { check(!active) { "freed during native operation" }; unloads++ }
        override fun generate(messages: ByteArray, maximum: Int, temperature: Float, topP: Float, seed: Int, callback: TokenCallback): LongArray {
            callback.onStart(false)
            for (byte in "你<think>隐藏</think>好🌿".toByteArray()) callback.onToken(byteArrayOf(byte))
            if (blockingGeneration) waitForStop()
            return longArrayOf(2, 5, 1, 10, if (stopped.get()) 1 else 0)
        }
    }
    private val model = ModelInfo("test", "test.gguf", ModelLocation.LOCAL)
    @Test fun streamsOrderedBodyAndClosesReplacedDescriptor() = runBlocking {
        val bridge = Bridge()
        val engine = LocalModelEngine { bridge }
        val first = Descriptor()
        val second = Descriptor()
        engine.load(model, EngineOptions()) { first }
        assertEquals("你好🌿", engine.generate(listOf(ChatMessage(ChatRole.USER, "hello"))).toList().joinToString(""))
        engine.load(model.copy(id = "next"), EngineOptions()) { second }
        assertTrue(first.closed)
        assertFalse(second.closed)
        engine.unload()
        assertTrue(second.closed)
        assertNull(engine.loadedModel.value)
    }
    @Test fun collectorCancellationAbortsJniBeforeUnload() = runBlocking {
        val bridge = Bridge().apply { blockingGeneration = true }
        val engine = LocalModelEngine { bridge }
        val file = Descriptor()
        engine.load(model, EngineOptions()) { file }
        val job = launch(Dispatchers.Default) { engine.generate(listOf(ChatMessage(ChatRole.USER, "hi"))).toList() }
        assertTrue(bridge.started.await(2, TimeUnit.SECONDS))
        withTimeout(1000) { job.cancelAndJoin() }
        assertEquals(0L, bridge.exited.count)
        engine.unload()
        assertTrue(file.closed)
    }
    @Test fun cancellationDuringLoadReleasesTheOnlyModelAndFile() = runBlocking {
        val bridge = Bridge().apply { blockingLoad = true }
        val engine = LocalModelEngine { bridge }
        val file = Descriptor()
        val job = launch(Dispatchers.Default) { engine.load(model, EngineOptions()) { file } }
        assertTrue(bridge.started.await(2, TimeUnit.SECONDS))
        withTimeout(1000) { job.cancelAndJoin() }
        assertTrue(file.closed)
        assertNull(engine.loadedModel.value)
        assertEquals(0L, bridge.exited.count)
    }
    @Test fun explicitStopEndsGenerationWithoutCancellingCollector() = runBlocking {
        val bridge = Bridge().apply { blockingGeneration = true }
        val engine = LocalModelEngine { bridge }
        engine.load(model, EngineOptions()) { Descriptor() }
        val job = launch(Dispatchers.Default) { engine.generate(listOf(ChatMessage(ChatRole.USER, "hi"))).toList() }
        assertTrue(bridge.started.await(2, TimeUnit.SECONDS))
        engine.stop()
        withTimeout(1000) { job.join() }
        assertTrue(engine.lastStats!!.stopped)
        engine.unload()
    }
}

