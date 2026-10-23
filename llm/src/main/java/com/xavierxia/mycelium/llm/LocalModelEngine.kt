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
    /** 只给真机测例用：打开后模板按「思考」渲染，默认关。 */
    external fun setThinking(thinking: Boolean)
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

    /**
     * 只给真机测例用（P2-1c 隐藏段三项）：打开后模板按「思考」渲染。默认关，与之前写死的行为一致；
     * 测例跑完必须关回去。
     */
    suspend fun setThinking(thinking: Boolean) = gate.withLock {
        (native as? NativeBridge)?.setThinking(thinking)
    }

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
    /** 两个 token 序列的公共前缀长度，与原生侧算的是同一个定义。 */
    private fun reusablePrefix(prompt: IntArray): Int {
        val cached = cachedPrompt?.takeIf { cacheOwner != null } ?: return 0
        val limit = minOf(cached.size, prompt.size - 1)
        var shared = 0
        while (shared < limit && cached[shared] == prompt[shared]) shared++
        return shared
    }

    /**
     * 把原生读数解析成这一轮的统计：正常路径与取消收尾共用同一套校验（方案审 r2 必修 2）。
     * [values] 为 null 表示「没有读数」（真取消/原生没回），返回 null，由调用方标成 incomplete。
     * 任何一项不自洽都抛错——坏读数不许被发布成有效测量。
     */
    private fun readingsOf(
        values: LongArray?,
        prompt: IntArray,
        requested: Int,
        maxTokens: Int,
        hiddenTokens: Long,
        hiddenMillis: Long,
    ): GenerationStats? {
        if (values == null) return null
        if (values.size != 8) {
            error("原生读数应有 8 项（提示 token、生成 token、读提示毫秒、生成毫秒、是否停止、复用 token、实读 token、未复用原因），实际 ${values.size} 项")
        }
        if (values[0] != prompt.size.toLong()) {
            error("原生报的提示长度 ${values[0]} 与本侧 tokenize 出来的 ${prompt.size} 对不上")
        }
        val count = values[0]
        val generated = values[1]
        val promptMillis = values[2]
        val generationMillis = values[3]
        val stopBit = values[4]
        val reused = values[5]
        val read = values[6]
        val reason = values[7]
        if (stopBit !in 0L..1L) error("原生报的停止位 $stopBit 不认识")
        val stopped = stopBit == 1L
        if (generated < 0 || generated > maxTokens.toLong()) {
            error("原生报的生成 token 数 $generated 超出这一轮的预算 $maxTokens")
        }
        if (promptMillis < 0 || generationMillis < 0) {
            error("原生报的耗时为负：读提示 $promptMillis、生成 $generationMillis")
        }
        val reuseCeiling = minOf(requested, prompt.size - 1).toLong()
        if (reused < 0 || reused > reuseCeiling) {
            error("原生报的复用数 $reused 超出这一轮请求的 $requested（上限 $reuseCeiling）")
        }
        if (read < 0 || read > count - reused) {
            error("原生报的实读数 $read 不合理：整段 $count、复用 $reused")
        }
        if (!stopped && reused + read != count) {
            error("这一轮没有停止却没把提示读完：复用 $reused + 实读 $read ≠ 整段 $count")
        }
        if (reused + read != count && generated != 0L) {
            error("提示还没读完就报了生成 token：读完 ${reused + read}/$count、生成 $generated")
        }
        // P2-1d：原因码范围扩到 0..7（5/6/7 是检查点路径的码，见 CheckpointFallback）。
        // 方案审必修项 5：只改范围不够，下面几条一致性校验要按**执行路径**分开判，否则
        // 「首轮报 5」「开关关着报 7」会被这里当协议错误直接抛掉，真机读数会是零。
        if (reason !in 0L..7L) error("原生报的未复用原因码 $reason 不认识")
        if (reason == 4L) error("原生报了这一轮 decode 失败（原因 4），这一轮不能当读数用")
        // 「没有任何东西可复用」（原因 1）只属于「这一轮压根没请求复用」那条路；检查点的三个码
        // （5/6/7）也可能出现在 requested>0 的轮次里——它们说的是「检查点没帮上」，不是「没请求」。
        val checkpointReason = reason in 5L..7L
        if (!checkpointReason && (reason == 1L) != (requested == 0)) {
            error("原因码 $reason 与这一轮请求的复用数 $requested 不一致")
        }
        // 说「没复用」的码：2/3（P2-1c）与 5/6/7（P2-1d）都必须是 reused == 0。
        if ((reason == 2L || reason == 3L || checkpointReason) && reused != 0L) {
            error("原因码 $reason 说没有复用，却报了复用 $reused")
        }
        // 请求了复用、报「成功复用」（原因 0），却一个都没复用：没有理由。
        if (reason == 0L && reused == 0L && requested > 0) {
            error("这一轮请求了复用却没复用，也没给出原因")
        }
        return GenerationStats(count, generated, promptMillis, generationMillis, stopped, reused, read, reason, hiddenTokens, hiddenMillis)
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

    /**
     * 把一段消息按模型自带的聊天模板编成 token 序列（P2-1d）。
     *
     * 检查点要存在「位置不超过公共前缀」的地方，所以本侧必须先能拿到某一轮提示的 token 序列，
     * 再和上一轮的比较、算出分叉点。用的格式与 [generate] 完全一致（同一个 payload、同一个原生
     * `tokenize`），否则比较出来的前缀没有意义。
     */
    suspend fun promptTokenIds(messages: List<ChatMessage>): IntArray {
        val snapshot = messages.toList()
        require(snapshot.isNotEmpty() && snapshot.sumOf { it.content.length.toLong() } <= 1_000_000) { "消息为空或过长" }
        val json = JSONArray()
        snapshot.forEach { json.put(JSONObject().put("role", it.role.name.lowercase()).put("content", it.content)) }
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        return gate.withLock {
            checkNotNull(loaded.value) { "请先加载模型" }
            runNative { native.tokenize(payload) }
        }
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
                // 这一轮实际请求复用的 token 数：收尾校验要用，所以声明在 try 之外。
                var requested = 0
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
                    requested = 0
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
                                    val now = System.nanoTime()
                                    if (firstTokenNanos == 0L) firstTokenNanos = now
                                    if (filter.sawHiddenText) hiddenTokens++
                                    // 第一个 token 就是正文时，等待就是 0：不能把同一次回调里的几微秒
                                    // 也算成「等过」（否则「没有隐藏段就没有等待」会随机器抖动）。
                                    if (firstVisibleNanos == 0L && text.isNotBlank()) firstVisibleNanos = now
                                    return text.isEmpty() || trySendBlocking(text).isSuccess
                                }
                            })).also { lastNativeReadings = it }
                    }
                    // 读数一律走同一套校验（正常路径与取消收尾共用）；任何一项不自洽都会抛错，
                    // 坏读数不许被发布成有效测量（方案审 r2 必修 2）。
                    val stats = requireNotNull(
                        readingsOf(values, prompt, requested, parameters.maxTokens, hiddenTokens, hiddenMillis(firstTokenNanos, firstVisibleNanos)),
                    ) { "这一轮必须给出读数" }
                    lastStats = stats
                    // Only a prompt that was read to its end is a cache entry the next round may
                    // reuse. A round that was stopped while the prompt was still being read forgets the
                    // cache instead of carrying a half-read prompt into the next one. A stop after the
                    // prompt was read is fine: the next round trims back to the shared prefix anyway.
                    if (cacheKey != null) {
                        if (stats.reusedPromptTokens + stats.readPromptTokens == values[0]) {
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
                    // 收尾统计：有可靠读数就用，读数不合格就当「没有读数」。这里绝不能因为解析出错
                    // 而顶掉原来那个异常（方案审 r2 必修 2）。
                    val readings = lastNativeReadings
                    lastNativeReadings = null
                    val stats = runCatching {
                        readingsOf(
                            readings?.second,
                            readings?.first ?: IntArray(0),
                            requested,
                            parameters.maxTokens,
                            hiddenTokens,
                            hiddenMillis(firstTokenNanos, firstVisibleNanos),
                        )
                    }.getOrNull()
                    val cancelled = failure is kotlinx.coroutines.CancellationException
                    lastStats = stats ?: GenerationStats(
                        0, 0, 0, 0,
                        stopped = cancelled,
                        hiddenTokens = hiddenTokens,
                        hiddenMillis = hiddenMillis(firstTokenNanos, firstVisibleNanos),
                        nativeReadingsComplete = false,
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



