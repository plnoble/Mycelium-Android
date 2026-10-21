package com.xavierxia.mycelium.llm

import com.xavierxia.mycelium.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
        /** 这个桥的提示固定是 2 个 token（见下面回的读数），够本用例用。 */
        override fun tokenize(messages: ByteArray): IntArray = intArrayOf(1, 2)
        override fun generate(messages: ByteArray, maximum: Int, temperature: Float, topP: Float, seed: Int, reuseTokens: Int, callback: TokenCallback): LongArray {
            callback.onStart(false)
            for (byte in "你<think>隐藏</think>好🌿".toByteArray()) callback.onToken(byteArrayOf(byte))
            if (blockingGeneration) waitForStop()
            // 第 6、7 项是 P2-1c 的读数：这一轮没有复用，整段 2 个 token 都读了；第 8 项是没复用的原因。
            return longArrayOf(2, 5, 1, 10, if (stopped.get()) 1 else 0, 0, 2, 1)
        }
    }
    private val model = ModelInfo("test", "test.gguf", ModelLocation.LOCAL)

    /**
     * native 侧最坏情况的加载：它只在自己的检查点看取消标记，所以「停止信号已经到了、加载却仍然
     * 成功返回」完全可能。这个桥就照这个样子工作：等到停止信号，然后照常返回。
     */
    private class StopBlindLoadBridge(private val failWithLoadingStopped: Boolean = false) : EngineBridge {
        val started = CountDownLatch(1)
        private val stopped = AtomicBoolean(false)
        val unloads = AtomicInteger()
        val generates = AtomicInteger()
        override fun begin() { stopped.set(false) }
        override fun stop() { stopped.set(true) }
        override fun load(fd: Int, threads: Int, context: Int) {
            started.countDown()
            if (failWithLoadingStopped) throw IllegalStateException("Loading stopped")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!stopped.get() && System.nanoTime() < deadline) Thread.sleep(1)
        }
        override fun unload() { unloads.incrementAndGet() }
        /** 这个桥的提示固定是 1 个 token（见下面的读数）。 */
        override fun tokenize(messages: ByteArray): IntArray = intArrayOf(1)
        override fun generate(messages: ByteArray, maximum: Int, temperature: Float, topP: Float, seed: Int, reuseTokens: Int, callback: TokenCallback): LongArray {
            generates.incrementAndGet()
            callback.onStart(false)
            // 第 6、7 项是 P2-1c 的读数：没有复用，那唯一的 token 被读了；第 8 项是没复用的原因。
            return longArrayOf(1, 0, 0, 1, 0, 0, 1, 1)
        }
    }

    /**
     * 加载返回之后、开始生成之前必须认下这一轮的停止：native 加载「照常成功」时引擎也不许留下
     * 已加载的模型 —— 否则随后那次 generate 的 runNative 会把停止标记清掉，接着照样生成、照样入库。
     * 这里同时钉住三件事：加载以失败收场、模型与文件描述符都被释放、后面那一步生成到不了 native。
     */
    @Test fun aStopThatLandsDuringASuccessfulLoadLeavesNothingLoaded() = runBlocking {
        val bridge = StopBlindLoadBridge()
        val engine = LocalModelEngine { bridge }
        val file = Descriptor()
        val loading = async(Dispatchers.Default) {
            try {
                engine.load(model, EngineOptions()) { file }
                null
            } catch (failure: Exception) {
                failure
            }
        }
        assertTrue(bridge.started.await(2, TimeUnit.SECONDS))
        engine.stop()
        val failure = loading.await()
        assertTrue("停止之后这次加载必须以失败收场，实际 $failure", failure is IllegalStateException)
        assertEquals("Loading stopped", failure?.message)
        assertNull("停止过的加载不许留下已加载的模型", engine.loadedModel.value)
        assertTrue("刚打开的描述符必须关掉", file.closed)
        val generated = runCatching { engine.generate(listOf(ChatMessage(ChatRole.USER, "hi"))).toList() }
        assertTrue("没有已加载的模型时生成必须失败", generated.isFailure)
        assertEquals("不该真的开始生成", 0, bridge.generates.get())
    }

    /**
     * 原 `Loading stopped` 异常路径：native 加载自己抛错也要清干净（模型、文件描述符），并把这次
     * 失败原样交给调用方 —— 炉边据此把这一轮按「停止」收尾：问题回输入框、不入库。
     */
    @Test fun aNativeLoadingStoppedFailureReleasesTheModelAndTheFile() = runBlocking {
        val bridge = StopBlindLoadBridge(failWithLoadingStopped = true)
        val engine = LocalModelEngine { bridge }
        val file = Descriptor()
        val failure = try {
            engine.load(model, EngineOptions()) { file }
            null
        } catch (failure: Exception) {
            failure
        }
        assertTrue("`Loading stopped` 应该原样交给调用方，实际 $failure", failure is IllegalStateException)
        assertEquals("Loading stopped", failure?.message)
        assertNull(engine.loadedModel.value)
        assertTrue("失败之后描述符必须关掉", file.closed)
        assertEquals("不该真的开始生成", 0, bridge.generates.get())
    }

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

