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
    /** How many of [promptTokens] the engine already had and did not read again (P2-1c). */
    val reusedPromptTokens: Long = 0,
    /** How many tokens of this prompt were actually read. [promptMillis] measures this part only. */
    val readPromptTokens: Long = promptTokens,
    /**
     * Why this round read more than it could have (P2-1c): 0 = it reused everything it could,
     * 1 = nothing was asked for, 2 = the engine held nothing shareable, 3 = the model's state cannot
     * be rolled back that far. Only ever read for logs and the delivery report.
     */
    val reuseFallbackReason: Long = 0,
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

    /**
     * The token ids of the formatted prompt. The cache decision (P2-1c) is made on this side, on
     * top of the sequence the model is actually asked about, so [generate] takes the number of
     * leading tokens that are already in the engine's cache.
     */
    fun tokenize(messages: ByteArray): IntArray
    fun generate(messages: ByteArray, maximum: Int, temperature: Float, topP: Float, seed: Int, reuseTokens: Int, callback: TokenCallback): LongArray
}
internal class NativeBridge : EngineBridge {
    init { System.loadLibrary("mycelium_llm") }
    external override fun begin()
    external override fun stop()
    external override fun load(fd: Int, threads: Int, context: Int)
    external override fun unload()
    external override fun tokenize(messages: ByteArray): IntArray
    external override fun generate(messages: ByteArray, maximum: Int, temperature: Float, topP: Float, seed: Int, reuseTokens: Int, callback: TokenCallback): LongArray
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
    /**
     * Which conversation the prompt the engine read completely last time belonged to, and that
     * prompt's token ids (P2-1c). Both are only ever touched while [gate] is held. A [null]
     * [cacheKey] on a round means "reuse nothing, and leave this state alone".
     */
    private var cacheOwner: String? = null
    private var cachedPrompt: IntArray? = null
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
        forgetCachedPrompt()
        native.unload()
        val previous = descriptor
        descriptor = null
        loaded.value = null
        previous?.close()
    }

    private fun forgetCachedPrompt() {
        cacheOwner = null
        cachedPrompt = null
    }

    /**
     * How much of [prompt] is already in the engine's cache: the longest leading run that is
     * identical to what was read last time. At least one token always stays to be read — a round
     * that reads nothing at all would make "did the prompt get read completely?" unanswerable.
     */
    private fun reusablePrefix(prompt: IntArray): Int {
        val cached = cachedPrompt?.takeIf { cacheOwner != null } ?: return 0
        val limit = minOf(cached.size, prompt.size - 1)
        var shared = 0
        while (shared < limit && cached[shared] == prompt[shared]) shared++
        return shared
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
                val payload = json.toString().toByteArray(Charsets.UTF_8)
                val cacheKey = parameters.cacheKey
                var filter = ThinkingFilter()
                val utf8 = Utf8Stream()
                try {
                    // A round without a key must not leave a cached prompt behind either: on this one
                    // native context such a round clears the KV and reads everything, so afterwards the
                    // engine holds a prompt the old key does not describe. Keeping the old ownership
                    // would let a later round with that key reuse a prefix that belongs to another
                    // round. Nothing is reused here either way (plan review, must-fix 2).
                    if (cacheKey == null) forgetCachedPrompt()
                    val (prompt, values) = runNative {
                        // The sequence the model is actually asked about decides the reuse; a key that
                        // does not belong to the cached prompt means this round starts from scratch.
                        val tokens = native.tokenize(payload)
                        val reuse = if (cacheKey != null && cacheKey == cacheOwner) reusablePrefix(tokens) else 0
                        tokens to native.generate(payload, parameters.maxTokens,
                            parameters.temperature, parameters.topP, parameters.seed, reuse, object : TokenCallback {
                                override fun onStart(thinking: Boolean) { filter = ThinkingFilter(thinking) }
                                override fun onToken(bytes: ByteArray): Boolean {
                                    if (!isActive) return false
                                    val text = filter.push(utf8.push(bytes))
                                    return text.isEmpty() || trySendBlocking(text).isSuccess
                                }
                            })
                    }
                    // Strict contract: a bridge that does not report the reuse split cannot be told
                    // apart from one that reused nothing, and a count that disagrees with the sequence
                    // the engine was handed would make every later comparison meaningless. Both fail
                    // loudly instead of being taken as "reused nothing" (plan review, must-fix 5).
                    if (values.size != 8) {
                        error("原生读数应有 8 项（提示 token、生成 token、读提示毫秒、生成毫秒、是否停止、复用 token、实读 token、未复用原因），实际 ${values.size} 项")
                    }
                    if (values[0] != prompt.size.toLong()) {
                        error("原生报的提示长度 ${values[0]} 与本侧 tokenize 出来的 ${prompt.size} 对不上")
                    }
                    val reused = values[5]
                    val read = values[6]
                    if (reused < 0 || read < 0 || reused + read > values[0]) {
                        error("原生报的复用/实读数不合理：复用 $reused、实读 $read、整段 ${values[0]}")
                    }
                    lastStats = GenerationStats(
                        values[0], values[1], values[2], values[3], values[4] != 0L, reused, read, values[7],
                    )
                    // Only a prompt that was read to its end is a cache entry the next round may
                    // reuse. A round that was stopped while the prompt was still being read (or one
                    // whose numbers do not add up) forgets the cache instead of carrying a half-read
                    // prompt into the next one. A stop after the prompt was read is fine: the next
                    // round trims back to the shared prefix anyway.
                    if (cacheKey != null) {
                        if (reused >= 0 && reused + read == values[0]) {
                            cacheOwner = cacheKey
                            cachedPrompt = prompt
                        } else forgetCachedPrompt()
                    }
                    if (values[4] == 0L) {
                        val tail = filter.push(utf8.push(byteArrayOf(), last = true))
                        if (tail.isNotEmpty()) send(tail)
                    }
                } catch (failure: Throwable) {
                    // Cancellation and native failures alike leave a prompt that may be half read:
                    // drop the cache rather than hand it to the next round.
                    forgetCachedPrompt()
                    throw failure
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



