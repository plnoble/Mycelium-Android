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
     * P2-1d：这一轮在哪个位置存下了检查点（-1 = 没存）。存不下来的原因不在这里，而要看日志与原生
     * 读数——但「有没有存下、多大」必须能被看到，否则真机上「为什么没提速」没法归因。
     */
    val checkpointPos: Int = -1,
    /** P2-1d：这一轮存下的检查点字节数（0 = 没存）。 */
    val checkpointBytes: Long = 0,
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
    fun generate(messages: ByteArray, maximum: Int, temperature: Float, topP: Float, seed: Int, reuseTokens: Int, saveCheckpointAt: Int, restoredTo: Int, callback: TokenCallback): LongArray

    /**
     * P2-1d 的 S2：把刚才那一轮在跨过 [saveCheckpointAt] 时存下的检查点取走（取完清空）；没有给 null。
     *
     * 默认返回 null = 「这台实现不支持检查点」——纯注意力模型与旧实现因此完全不受影响。
     */
    fun takeCheckpoint(): ByteArray? = null

    /** 待取的检查点对应哪个位置；没有就是 -1。 */
    fun takeCheckpointPos(): Int = -1

    /** P2-1d 的 S3：恢复检查点并裁到恢复位置；两步都成才 true（方案审必修项 2）。 */
    fun restoreCheckpoint(data: ByteArray, pos: Int): Boolean = false

    /** 这个模型能不能走检查点路径（混合 / 循环才成）。 */
    fun canCheckpoint(): Boolean = false

    /** 把一串 token id 还原成文本；默认空串（这台实现不给解码）。 */
    fun decodeTokens(ids: IntArray): String = ""

    /** 上一次恢复失败在哪一步；默认 -1（没试过）。 */
    fun lastRestoreStep(): Int = -1

    /**
     * P2-1d 第 0 步的测量探针：对当前序列 0 的状态分别取 `NONE` 与 `PARTIAL_ONLY` 两份的大小、
     * 各存一次各恢复一次，并报耗时。**跑完会把整份状态装回去**，所以引擎状态不变。
     *
     * 默认返回 null = 「这台实现不支持」（旧桥与假桥都不受影响）。
     */
    fun measureCheckpoints(): LongArray? = null
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
    external override fun generate(messages: ByteArray, maximum: Int, temperature: Float, topP: Float, seed: Int, reuseTokens: Int, saveCheckpointAt: Int, restoredTo: Int, callback: TokenCallback): LongArray
    /** P2-1d 的 S2：把刚才那一轮在跨过 checkpointPos 时存下的检查点取走（取完清空）；没有给 null。 */
    external override fun takeCheckpoint(): ByteArray?
    /** 待取的检查点对应哪个位置；没有就是 -1。 */
    external override fun takeCheckpointPos(): Int
    /** P2-1d 的 S3：恢复检查点并裁到恢复位置；两步都成才 true（方案审必修项 2）。 */
    external override fun restoreCheckpoint(data: ByteArray, pos: Int): Boolean
    /** 这个模型能不能走检查点路径（混合 / 循环才成）。 */
    external override fun canCheckpoint(): Boolean
    /**
     * 把一串 token id 还原成文本（方案审必修项 1 的取证用）。
     *
     * 默认返回空串 = 「这台实现不给解码」，纯注意力与旧实现不受影响。
     */
    external override fun decodeTokens(ids: IntArray): String
    /** 上一次恢复失败在哪一步（0 成功 / 1 set_data 短读 / 2 seq_rm false / -1 没试过）。 */
    external override fun lastRestoreStep(): Int
    /**
     * P2-1d 第 0 步的测量探针：对当前序列 0 的状态分别取 `NONE` 与 `PARTIAL_ONLY` 两份的大小、
     * 各存一次各恢复一次，并报耗时。**跑完会把整份状态装回去**，所以引擎状态不变。
     *
     * 返回 null = 这台实现不支持（或没加载模型）。
     */
    external override fun measureCheckpoints(): LongArray?
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

    // ---- P2-1d：检查点路径（S2/S3/S4）-----------------------------------------------------
    /**
     * 总开关。**默认关**（方案审必修项 7）：真机逐字比对没做之前不许默认开；比对通过以后由统筹决定打开。
     * 关着时走 P2-1c 的老路，一个检查点接口都不碰。
     */
    @Volatile var checkpointEnabled: Boolean = false

    /** 检查点仓库（S1）。只在持有 [gate] 锁的代码里动它——和缓存状态同一把锁，同一套并发前提。 */
    private val checkpoints = CheckpointStore()

    /** 这一轮恢复到的位置；-1 表示没走恢复（给统计与日志用）。 */
    @Volatile var checkpointRestoredFrom: Int = -1
        private set

    /** 这一轮没走检查点的原因码（5/6/7），0 表示这条路没被触发。 */
    @Volatile var checkpointFallbackReason: Long = 0L
        private set

    /**
     * 「系统说内存紧张，下次进锁先把检查点整批丢掉」（Codex R2 必修项 2）。
     *
     * 用标记而不是在回调里直接清：仓库约定只在 `gate` 锁内访问，而 `onTrimMemory` 在**主线程**上，
     * 同步去拿推理锁会把主线程卡在一整轮生成里。
     */
    private val trimRequested = AtomicBoolean(false)

    /** 在锁内调用：有「该清了」的标记就整批丢掉。 */
    private fun applyPendingTrim() {
        if (trimRequested.compareAndSet(true, false)) forgetCheckpoints()
    }

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
        // 换模型 / 卸载 / 引擎清理：检查点跟着一起作废（它们是**这个模型、这个上下文**里的状态）。
        forgetCheckpoints()
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
     * 把检查点一起忘掉（P2-1d 必修项 3 的生命周期那一半）。
     *
     * 换模型、卸载、引擎清理都要调它：检查点是**某个模型、某个上下文**里的状态，换了模型再拿旧字节去
     * 恢复，等于往新模型里灌另一份状态。
     */
    private fun forgetCheckpoints() {
        checkpoints.clear()
        checkpointRestoredFrom = -1
        checkpointFallbackReason = 0L
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
        // P2-1d：原生读数从 8 项扩到 10 项（方案审必修项 5：加检查点统计必须一起扩）。旧桥仍然回 8 项，
        // 缺的两项按「没有检查点」处理——这样同一个引擎既能跑真桥也能跑老假桥。
        if (values.size != 8 && values.size != 10) {
            error("原生读数应有 8 或 10 项（第 9/10 项是检查点位置与字节数），实际 ${values.size} 项")
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
        return GenerationStats(
            promptTokens = count, generatedTokens = generated, promptMillis = promptMillis,
            generationMillis = generationMillis, stopped = stopped, reusedPromptTokens = reused,
            readPromptTokens = read, reuseFallbackReason = reason, hiddenTokens = hiddenTokens,
            hiddenMillis = hiddenMillis,
            // P2-1d：原生交回来的检查点落点与字节数（旧桥缺这两项，按「没有」处理）。
            checkpointPos = values.getOrNull(8)?.toInt() ?: -1,
            checkpointBytes = values.getOrNull(9) ?: 0L,
        )
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

    /**
     * 把 token id 还原成文本（P2-1d 必修项 1 的取证：边界两侧各自解码出什么）。
     *
     * 只有真机测例用它；界面上不显示。
     */
    suspend fun decodeTokens(ids: IntArray): String {
        require(ids.isNotEmpty()) { "没有 token 可解码" }
        return gate.withLock {
            checkNotNull(loaded.value) { "请先加载模型" }
            runNative { native.decodeTokens(ids) }
        }
    }

    /** 这个模型能不能走检查点路径（混合 / 循环才成）。真机取证与界面提示都用它。 */
    fun canCheckpoint(): Boolean = native.canCheckpoint()

    /** 上一次恢复失败在哪一步（0 成功 / 1 set_data 短读 / 2 seq_rm false / -1 没试过）。 */
    fun lastRestoreStep(): Int = native.lastRestoreStep()

    /** 第 0 步测量探针（见 [EngineBridge.measureCheckpoints]）。 */
    fun measureCheckpoints(): LongArray? = native.measureCheckpoints()

    /**
     * 系统内存紧张时整批丢掉检查点（Codex R2 必修项 1/2）。
     *
     * 为什么还要**主动预算检查**：Android 16 起 `TRIM_MEMORY_RUNNING_*` 这类级别不再下发给应用，
     * 单靠这个回调兜不住。所以主防线是保存前的准入（单份超上限就不存），这里只是额外的保险。
     *
     * 实现上**不碰 `gate` 锁**：从 `onTrimMemory` 回调里同步拿推理锁会把主线程卡在一整轮生成上。
     * 改为在同一个引擎上只打一个「该清了」的标记，下一次真正进锁时先清。
     */
    fun requestCheckpointTrim() {
        trimRequested.set(true)
    }

    /** 系统真的低内存时调用：同上，只是语义更重。 */
    fun onLowMemory() = requestCheckpointTrim()

    /** 上一次「取检查点」的经过（字节/位置/落点），只给调试与真机读数用。 */
    @Volatile var lastTakeDiagnosis: String = ""
        private set

    /** 检查点仓库现在的占用（份数与字节），只给真机读数与日志用。 */
    fun checkpointUsage(): Pair<Int, Long> = checkpoints.size to checkpoints.totalBytes

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
                // 系统在这之前说过内存紧张：现在已经在锁里了，安全地把检查点整批丢掉。
                applyPendingTrim()
                // 每轮重置检查点诊断字段（Codex R2 必修项 4）：否则「上一轮恢复了」会挂在这一轮上，
                // 界面与日志读到的就是别人的结论。
                checkpointRestoredFrom = -1
                checkpointFallbackReason = 0L
                // 这一轮实际请求复用的 token 数：收尾校验要用，所以声明在 try 之外。
                var requested = 0
                var filter = ThinkingFilter()
                val utf8 = Utf8Stream()
                // P2-1c 的 E：隐藏思考段用了多少 token、等了多久。只记账，不改生成内容。
                var hiddenTokens = 0L
                var firstTokenNanos = 0L
                var firstVisibleNanos = 0L
                // P2-1d：这一轮要存检查点的落点（声明在 try 之外：收尾取字节时也要用它）。
                var checkpointAt = -1
                // P2-1d：这一轮的状态是否刚从检查点恢复好（>= 0 即恢复位置）。原生据此**直接**从
                // 那里读，不再走「共享前缀 + 自己裁」那条判断——那条对混合模型会失败并整段重读。
                var restoredTo = -1
                try {
                    // 换会话或无键轮次（全量审必修项 4）：检查点与缓存一起作废。
                    // 引擎本来就只有一组 cacheOwner/cachedPrompt，换了键就不能再保留上一个会话的状态。
                    if (cacheKey != cacheOwner) {
                        cacheOwner?.let { checkpoints.invalidate(it) }
                        forgetCachedPrompt()
                    }
                    if (cacheKey == null) {
                        forgetCachedPrompt()
                        forgetCheckpoints()
                    }
                    requested = 0
                    // P2-1d：这一轮要不要存检查点，**在进原生之前**就定下来（原生那段跑在别的线程上，
                    // 在里面写外面的变量不安全，第一版就因此丢掉过落点）。
                    val checkpointWanted = checkpointEnabled && parameters.checkpointPos > 0 &&
                        cacheKey != null && native.canCheckpoint()
                    if (checkpointWanted) checkpointAt = parameters.checkpointPos
                    val (prompt, values) = runNative {
                        // The sequence the model is actually asked about decides the reuse; a key that
                        // does not belong to the cached prompt means this round starts from scratch.
                        val tokens = native.tokenize(payload)
                        // 全量审必修项 1（P1）：挑选检查点的上限必须是**真实公共前缀**，绝不能是目标落点！
                        // 目标落点（checkpointPos）是「这一轮想在哪存」，真实公共前缀（shared）是「和上一轮
                        // 重合了多少」。若会话被编辑，shared 只有 2 而 checkpointPos 是 15，传 checkpointPos
                        // 就会挑中 15 的旧快照，把另一条分支的状态恢复出来。
                        val shared = if (cacheKey != null && cacheKey == cacheOwner) reusablePrefix(tokens) else 0
                        requested = shared
                        if (checkpointWanted) {
                            // 先作废超出真实公共前缀的检查点（分叉处理，全量审必修项 1/4）
                            checkpoints.invalidateBeyond(cacheKey, shared)
                            when (val plan = planPrefix(
                                store = checkpoints,
                                key = cacheKey,
                                maxPos = shared,
                                enabled = true,
                                checkpointable = true,
                            )) {
                                is PrefixPlan.Restore -> {
                                    // 全量审必修项 2（P1）：恢复失败必须「清干净、从 0 重读」。
                                    if (native.restoreCheckpoint(plan.checkpoint.bytes, plan.checkpoint.pos)) {
                                        checkpointRestoredFrom = plan.checkpoint.pos
                                        restoredTo = plan.checkpoint.pos
                                        // 恢复后从 cp 接着读：这一轮的「复用数」就是 cp。
                                        requested = plan.checkpoint.pos
                                        cacheOwner = cacheKey
                                        checkpointFallbackReason = 0L
                                    } else {
                                        // 恢复失败：作废坏快照、清缓存元数据、从 0 重读，绝不能留着
                                        // requested>0 去撞原生，否则收尾统计自相矛盾（reason=6, reused>0）。
                                        checkpoints.invalidate(cacheKey)
                                        forgetCachedPrompt()
                                        requested = 0
                                        restoredTo = -1
                                        checkpointRestoredFrom = -1
                                        checkpointFallbackReason = CheckpointFallback.RestoreFailed.code
                                    }
                                }
                                is PrefixPlan.FullReread -> {
                                    checkpointFallbackReason = plan.reason?.code ?: 0L
                                }
                                PrefixPlan.LegacyPrefixCache -> Unit
                            }
                        }
                        // 读数在原生回来的那一刻就存下来：收集方被取消时 `runNative` 会抛，但它其实
                        // 还是等到了 worker 退出，这份读数照样能用（方案审必修 6）。
                        (tokens to native.generate(payload, parameters.maxTokens,
                            parameters.temperature, parameters.topP, parameters.seed, requested, checkpointAt, restoredTo,
                            object : TokenCallback {
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
                    // Codex R2 必修项 4：检查点路径的回退原因必须**真正进统计**（原来只落在旁路字段里，
                    // 交付报告与界面读的是这一份）。非 0 时优先于原生那个旧码——原生只知道 P2-1c 的
                    // 1/2/3，不知道检查点为什么没用上。正常结束与取消收尾共用这一段。
                    lastStats = stats.copy(
                        reuseFallbackReason = if (checkpointFallbackReason != 0L) checkpointFallbackReason else stats.reuseFallbackReason,
                    )
                    // P2-1d 的 S2：把这一轮在「跨过 checkpointPos」时存下的检查点取回来记进仓库。
                    // 位置与字节都由原生交回来，本侧不猜；取不到（没到落点、存失败、或模型不适用）
                    // 就什么也不记——不许拿别的位置顶替。
                    if (checkpointAt > 0 && cacheKey != null) {
                        // **先问位置、再取字节**：原生的 takeCheckpoint() 会把位置一起作废，顺序反了就
                        // 永远拿到 -1（第一版就是这么错的：字节 52 MB 都取到了，位置却一直是 -1，
                        // 检查点因此永远进不了仓库）。
                        val savedPos = native.takeCheckpointPos()
                        val saved = native.takeCheckpoint()
                        lastTakeDiagnosis = "take=${saved?.size ?: -1}/pos=$savedPos/at=$checkpointAt"
                        if (saved != null && savedPos > 0) {
                            checkpoints.put(cacheKey, savedPos, saved)
                        } else {
                            // 没存成：把上一轮留下的、位置可能已经不对的检查点作废，别让下轮选中它。
                            checkpoints.invalidateBeyond(cacheKey, checkpointAt)
                        }
                    } else {
                        native.takeCheckpoint()
                        native.takeCheckpointPos()
                    }
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
                    // 全量审必修项 4：异常与取消时，检查点必须跟着作废，不能保留不可信的状态；
                    // 在途的原生缓冲也必须取走丢掉，不占用原生内存（必修项 3）。
                    forgetCheckpoints()
                    native.takeCheckpoint()
                    native.takeCheckpointPos()
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
                        // 取消收尾也要带上检查点回退原因（同一套口径）。
                        reuseFallbackReason = checkpointFallbackReason,
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



