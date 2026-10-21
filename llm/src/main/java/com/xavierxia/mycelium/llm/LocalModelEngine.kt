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
    /**
     * How many native tokens arrived while the answer was still inside a hidden reasoning span
     * (P2-1c, step E). A token that opens or closes the span counts as hidden. Purely diagnostic:
     * the body text and the generated-token count above are unchanged.
     */
    val hiddenTokens: Long = 0,
    /**
     * Milliseconds from the first native token to the first non-blank visible body text (P2-1c,
     * step E). Zero when the answer started with visible text. It measures the generation phase only
     * — prompt reading, template work and model loading are not inside it — so it must never be
     * quoted as "time until the user sees the first character".
     */
    val hiddenMillis: Long = 0,
    /**
     * False when this round ended without the native readings coming back (a real cancellation, or a
     * native call that failed before reporting). The token and time numbers are then unknown, not
     * zero, and must not be quoted as measurements (plan review, must-fix 6).
     */
    val nativeReadingsComplete: Boolean = true,
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

    /**
     * 原生最后交回来的那一份读数（提示 token 序列 + 8 项读数）。取消发生时，收集方会被取消，但
     * `runNative` 仍然 join 了 worker，所以这份读数可能是齐的：收尾统计用它，而不是编一组零。
     * 只在它自己那一轮里有效（每轮开头清、收尾用掉即清）。
     */
    @Volatile private var lastNativeReadings: Pair<IntArray, LongArray>? = null
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

    /**
     * Milliseconds from this round's first native token to the first non-blank visible body text
     * (P2-1c, step E). Zero when no token arrived or the very first one was visible text.
     *
     * **Known limits, by construction** (full review, must-fix 5): it measures the wait for the
     * first visible text, not the sum of every hidden interval — a hidden span that appears after
     * visible text has started is not timed, and pure whitespace / waiting for a UTF-8 sequence
     * counts as part of the wait. It also excludes prompt reading, template work and model loading,
     * so it must never be quoted as "time until the user sees the first character".
     */
    private fun hiddenMillis(firstTokenNanos: Long, firstVisibleNanos: Long): Long {
        if (firstTokenNanos == 0L) return 0
        val visible = if (firstVisibleNanos == 0L) System.nanoTime() else firstVisibleNanos
        return ((visible - firstTokenNanos) / 1_000_000).coerceAtLeast(0)
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
                // 上一轮的读数只在它自己那一轮里用；每一轮开头清掉，免得取消收尾读到旧的一份。
                lastNativeReadings = null
                var filter = ThinkingFilter()
                val utf8 = Utf8Stream()
                // P2-1c 的 E：隐藏思考段用了多少 token、等了多久。只记账，不改生成内容。
                var hiddenTokens = 0L
                var firstTokenNanos = 0L
                var firstVisibleNanos = 0L
                try {
                    // A round without a key must not leave a cached prompt behind either: on this one
                    // native context such a round clears the KV and reads everything, so afterwards the
                    // engine holds a prompt the old key does not describe. Keeping the old ownership
                    // would let a later round with that key reuse a prefix that belongs to another
                    // round. Nothing is reused here either way (plan review, must-fix 2).
                    if (cacheKey == null) forgetCachedPrompt()
                    var requested = 0
                    val (prompt, values) = runNative {
                        // The sequence the model is actually asked about decides the reuse; a key that
                        // does not belong to the cached prompt means this round starts from scratch.
                        val tokens = native.tokenize(payload)
                        val reuse = if (cacheKey != null && cacheKey == cacheOwner) reusablePrefix(tokens) else 0
                        requested = reuse
                        // 读数在原生回来的那一刻就存下来：收集方被取消时 `runNative` 会抛，但它其实
                        // 还是等到了 worker 退出，这份读数照样能用（方案审必修 6）。
                        (tokens to native.generate(payload, parameters.maxTokens,
                            parameters.temperature, parameters.topP, parameters.seed, reuse, object : TokenCallback {
                                override fun onStart(thinking: Boolean) { filter = ThinkingFilter(thinking) }
                                override fun onToken(bytes: ByteArray): Boolean {
                                    if (!isActive) return false
                                    // 归属由过滤器自己报：这个 token 消耗/保留的文本只要有一段处在隐藏
                                    // 状态（开标签、闭标签、整段都在一个 token 里），就算隐藏 token。
                                    val text = filter.push(utf8.push(bytes))
                                    if (firstTokenNanos == 0L) firstTokenNanos = System.nanoTime()
                                    if (filter.sawHiddenText) hiddenTokens++
                                    if (firstVisibleNanos == 0L && text.isNotBlank()) firstVisibleNanos = System.nanoTime()
                                    return text.isEmpty() || trySendBlocking(text).isSuccess
                                }
                            })).also { lastNativeReadings = it }
                    }
                    // 严格计数契约（方案审必修 4）：长度、提示长度一致、以及**每一项**读数的范围都要自洽。
                    // 任何一项对不上就立刻失败，不把不一致的读数当成「这一轮没复用」。
                    if (values.size != 8) {
                        error("原生读数应有 8 项（提示 token、生成 token、读提示毫秒、生成毫秒、是否停止、复用 token、实读 token、未复用原因），实际 ${values.size} 项")
                    }
                    if (values[0] != prompt.size.toLong()) {
                        error("原生报的提示长度 ${values[0]} 与本侧 tokenize 出来的 ${prompt.size} 对不上")
                    }
                    val reused = values[5]
                    val read = values[6]
                    val stopped = values[4] != 0L
                    val reuseCeiling = minOf(requested, prompt.size - 1).toLong()
                    if (values[1] < 0 || values[2] < 0 || values[3] < 0) {
                        error("原生报的生成 token 数或耗时为负：${values.toList()}")
                    }
                    if (reused < 0 || reused > reuseCeiling) {
                        error("原生报的复用数 $reused 超出这一轮请求的 $requested（上限 $reuseCeiling）")
                    }
                    if (read < 0 || read > values[0] - reused) {
                        error("原生报的实读数 $read 不合理：整段 ${values[0]}、复用 $reused")
                    }
                    if (!stopped && reused + read != values[0]) {
                        error("这一轮没有停止却没把提示读完：复用 $reused + 实读 $read ≠ 整段 ${values[0]}")
                    }
                    if (values[7] !in 0L..4L) {
                        error("原生报的未复用原因码 ${values[7]} 不认识")
                    }
                    lastStats = GenerationStats(
                        values[0], values[1], values[2], values[3], stopped, reused, read, values[7],
                        hiddenTokens, hiddenMillis(firstTokenNanos, firstVisibleNanos),
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
                    // 取消的是收集方，原生那一轮 worker 其实已经被 join 回来了：它交回的读数照样能用。
                    // 有就用真实读数收尾；没有就把这一轮标成「读数不完整」，不把未知量写成 0
                    // （方案审必修 6）。
                    val readings = lastNativeReadings
                    lastNativeReadings = null
                    val values = readings?.second
                    val cancelled = failure is kotlinx.coroutines.CancellationException
                    lastStats = GenerationStats(
                        promptTokens = values?.getOrNull(0) ?: 0L,
                        generatedTokens = values?.getOrNull(1) ?: 0L,
                        promptMillis = values?.getOrNull(2) ?: 0L,
                        generationMillis = values?.getOrNull(3) ?: 0L,
                        stopped = if (values != null) values[4] != 0L else cancelled,
                        reusedPromptTokens = values?.getOrNull(5) ?: 0L,
                        readPromptTokens = values?.getOrNull(6) ?: 0L,
                        reuseFallbackReason = values?.getOrNull(7) ?: 0L,
                        hiddenTokens = hiddenTokens,
                        hiddenMillis = hiddenMillis(firstTokenNanos, firstVisibleNanos),
                        nativeReadingsComplete = values != null,
                    )
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



