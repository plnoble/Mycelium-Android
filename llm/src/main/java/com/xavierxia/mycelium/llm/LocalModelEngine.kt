package com.xavierxia.mycelium.llm

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import com.xavierxia.mycelium.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

interface ModelFile : Closeable { val fd: Int }

data class EngineOptions(val threads: Int = 6, val contextLength: Int = 8192) {
    init {
        require(threads in 1..16) { "线程数须为 1–16" }
        require(contextLength in 512..32768) { "上下文长度须为 512–32768" }
    }
}
data class GenerationStats(
    val promptTokens: Long, val generatedTokens: Long, val promptMillis: Long,
    val generationMillis: Long, val stopped: Boolean,
) {
    val tokensPerSecond: Double get() = generatedTokens * 1000.0 / generationMillis.coerceAtLeast(1)
}

internal interface TokenCallback {
    fun onStart(thinking: Boolean)
    fun onToken(bytes: ByteArray): Boolean
}
internal interface EngineBridge {
    fun begin()
    fun stop()
    fun load(fd: Int, threads: Int, context: Int)
    fun unload()
    fun generate(messages: ByteArray, maximum: Int, temperature: Float, topP: Float, seed: Int, callback: TokenCallback): LongArray
}
internal class NativeBridge : EngineBridge {
    init { System.loadLibrary("mycelium_llm") }
    external override fun begin()
    external override fun stop()
    external override fun load(fd: Int, threads: Int, context: Int)
    external override fun unload()
    external override fun generate(messages: ByteArray, maximum: Int, temperature: Float, topP: Float, seed: Int, callback: TokenCallback): LongArray
}

/**
 * What a page that generates on this phone needs from the one local engine: the model it holds, the
 * statistics of the last generation, and the two operations that move it (load, generate). The hearth
 * page takes it from its own constructor, so a JVM test can put a fake behind exactly the boundary the
 * native engine sits behind instead of needing a phone and a model file.
 */
interface LocalEngine : ChatModel {
    val loadedModel: StateFlow<ModelInfo?>
    val lastStats: GenerationStats?
    suspend fun load(model: ModelInfo, options: EngineOptions, open: () -> ModelFile)
}

/** One process-wide engine. The mutex covers cleanup after cancellation as well. */
class LocalModelEngine internal constructor(private val bridgeFactory: () -> EngineBridge) : LocalEngine {
    companion object { val shared by lazy { LocalModelEngine { NativeBridge() } } }
    private val nativeDelegate = lazy(bridgeFactory)
    private val native by nativeDelegate
    private val gate = Mutex()
    private var descriptor: ModelFile? = null
    private val stopRequested = AtomicBoolean(false)
    private val loaded = MutableStateFlow<ModelInfo?>(null)
    override val loadedModel: StateFlow<ModelInfo?> = loaded.asStateFlow()
    override val info: ModelInfo get() = checkNotNull(loaded.value) { "请先加载模型" }
    @Volatile override var lastStats: GenerationStats? = null
        private set
    override fun stop() { stopRequested.set(true); if (nativeDelegate.isInitialized()) native.stop() }

    /** Opening and loading happen off the UI thread. The descriptor stays open until unload. */
    override suspend fun load(model: ModelInfo, options: EngineOptions, open: () -> ModelFile) {
        gate.withLock {
            try {
                runNative {
                    clear()
                    descriptor = open()
                    native.load(descriptor!!.fd, options.threads, options.contextLength)
                    // The native load can return successfully even though a stop landed while it was
                    // working: its flag is only read at its own checkpoints, and the very next
                    // runNative (the generate that would follow) starts by clearing it. Honour the
                    // stop here — the last moment before anybody can generate — so a stopped load
                    // becomes no loaded model and no answer, instead of one that silently continues.
                    check(!stopRequested.get()) { "Loading stopped" }
                    loaded.value = model
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { clear() }
                throw failure
            }
        }
    }
    suspend fun unload() {
        stop()
        gate.withLock { withContext(Dispatchers.IO) { if (nativeDelegate.isInitialized()) clear() } }
    }
    private fun clear() {
        native.unload()
        val previous = descriptor
        descriptor = null
        loaded.value = null
        previous?.close()
    }

    override fun generate(messages: List<ChatMessage>, parameters: GenerationParameters): Flow<String> {
        // Freeze caller-owned mutable input before asynchronous collection.
        val snapshot = messages.toList()
        require(snapshot.isNotEmpty() && snapshot.sumOf { it.content.length.toLong() } <= 1_000_000) { "消息为空或过长" }
        return channelFlow {
            gate.withLock {
                checkNotNull(loaded.value) { "请先加载模型" }
                lastStats = null
                val json = JSONArray()
                snapshot.forEach { json.put(JSONObject().put("role", it.role.name.lowercase()).put("content", it.content)) }
                var filter = ThinkingFilter()
                val utf8 = Utf8Stream()
                val values = runNative {
                    native.generate(json.toString().toByteArray(Charsets.UTF_8), parameters.maxTokens,
                        parameters.temperature, parameters.topP, parameters.seed, object : TokenCallback {
                            override fun onStart(thinking: Boolean) { filter = ThinkingFilter(thinking) }
                            override fun onToken(bytes: ByteArray): Boolean {
                                if (!isActive) return false
                                val text = filter.push(utf8.push(bytes))
                                return text.isEmpty() || trySendBlocking(text).isSuccess
                            }
                        })
                }
                lastStats = GenerationStats(values[0], values[1], values[2], values[3], values[4] != 0L)
                if (values[4] == 0L) {
                    val tail = filter.push(utf8.push(byteArrayOf(), last = true))
                    if (tail.isNotEmpty()) send(tail)
                }
            }
        }
    }

    /** await is cancellable even while JNI runs; join prevents concurrent unload/use-after-free. */
    private suspend fun <T> runNative(block: () -> T): T = coroutineScope {
        stopRequested.set(false)
        val worker = async(Dispatchers.IO) {
            native.begin()
            if (stopRequested.get()) native.stop()
            ensureActive()
            block()
        }
        try { worker.await() }
        finally {
            if (!currentCoroutineContext().isActive) stop()
            withContext(NonCancellable) { worker.join() }
        }
    }
}



