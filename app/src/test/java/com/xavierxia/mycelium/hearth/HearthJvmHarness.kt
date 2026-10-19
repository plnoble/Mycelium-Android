package com.xavierxia.mycelium.hearth

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.xavierxia.mycelium.llm.EngineOptions
import com.xavierxia.mycelium.llm.GenerationStats
import com.xavierxia.mycelium.llm.LocalEngine
import com.xavierxia.mycelium.llm.LocalModelRecord
import com.xavierxia.mycelium.llm.ModelFile
import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.GenerationParameters
import com.xavierxia.mycelium.model.ModelInfo
import java.io.File
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * JVM（Robolectric）上驱动 [HearthViewModel] 的公共夹具。
 *
 * 真机测试靠 `HearthDatabase`（SQLCipher）开会话；这里换成每个会话一条普通 sqlite 连接，并且用
 * [GatedJvmSessions] 把某一次读或某一次事务停在原地，时序因此可以一步一步摆出来，而不是靠 sleep
 * 去碰窗口。本地引擎在真机上是 JNI；这里用 [FakeLocalEngine] 顶在同一个边界上：测试能决定它什么
 * 时候「加载成功」、生成几个 token，从而把「停止落在生成启动之前」这件事钉死。
 *
 * 主线程用的是 Robolectric 的 looper（PAUSED），协程恢复到主线程的活由 [waitFor] 显式推进。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
abstract class HearthJvmTestCase {
    protected lateinit var context: Application
    private lateinit var databaseFile: File
    internal lateinit var gates: GatedJvmSessions
    protected lateinit var log: ConversationLog
    protected lateinit var characters: CharacterStore
    internal lateinit var engine: FakeLocalEngine
    protected lateinit var model: HearthViewModel
    private lateinit var record: LocalModelRecord
    private lateinit var store: ViewModelStore

    @Before
    fun setUpHarness() {
        context = RuntimeEnvironment.getApplication()
        registerSqliteDrivers()
        databaseFile = File(context.filesDir, "jvm-hearth-${System.nanoTime()}.sqlite")
        gates = GatedJvmSessions("jdbc:sqlite:${databaseFile.absolutePath}")
        log = ConversationLog(gates::open)
        characters = CharacterStore(gates::open)
        engine = FakeLocalEngine()
        record = seedModelRecord(context)
        // 走 ViewModelStore 建，收尾用 store.clear() 触发 onCleared()：viewModelScope 因此被取消，
        // 上一个用例的协程不会跑到下一个用例的库上。
        store = ViewModelStore()
        model = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HearthViewModel(context, gates::open, engine) as T
            },
        )[HearthViewModel::class.java]
    }

    @After
    fun tearDownHarness() {
        store.clear()
    }

    /** 一个用测试登记的假模型当默认引擎的人物。 */
    protected fun newCharacter(name: String): HearthCharacter = characters.create(
        CharacterDraft(
            name = name,
            personality = "JVM 用例用的临时人物。",
            greeting = "在的。",
            modelKind = ModelKind.LOCAL,
            modelId = record.sha256,
            modelName = record.name,
            maxTokens = 256,
        ),
        now(),
    )

    /** 等一个条件成立：每一步都推进主线程队列，等不到就让断言失败，而不是让测试挂住。 */
    protected fun waitFor(timeoutMs: Long = 20_000, what: String = "条件", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("等待超时（${timeoutMs}ms）：$what", condition())
    }

    /** 推进主线程队列一小段时间，用来断言「什么都不该发生」。 */
    protected fun settle(millis: Long = 400) {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /** 页面已经读过一次历史、可以发送了。 */
    protected fun openReady(character: HearthCharacter) {
        model.openChat(character)
        waitFor(what = "打开人物后历史读取完成") { !model.state.value.historyLoading }
    }

    /** 库里的时间戳用应用自己的格式，排序规则才一致。 */
    protected fun now(): String = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

    /** 比现在早 [minutes] 分钟的库内时间戳：两段历史的先后要定死。 */
    protected fun minutesAgo(minutes: Long): String =
        LocalDateTime.now().minusMinutes(minutes).truncatedTo(ChronoUnit.SECONDS).toString()
}

/**
 * Robolectric 给每个测试类一份自己的类加载器，sqlite 驱动因此在同一个 JVM 里会有两份拷贝，而
 * `DriverManager` 的 `isDriverAllowed` 只认「调用方加载器能看见的那一份」。如果本用例先跑，驱动
 * 的首次初始化就发生在沙箱加载器上，之后普通 JUnit 用例（用父加载器那一份）会直接报
 * `No suitable driver found`。所以两份都在这里显式登记一次：谁先跑都不会坑到另一份。
 */
private fun registerSqliteDrivers() {
    val loaders = listOfNotNull(JdbcHearthSession::class.java.classLoader, ClassLoader.getSystemClassLoader())
    loaders.distinct().forEach { loader ->
        runCatching {
            val driver = Class.forName("org.sqlite.JDBC", true, loader).getDeclaredConstructor().newInstance() as Driver
            DriverManager.registerDriver(driver)
        }
    }
}

/** 把测试登记的假模型写进 `AndroidModelStore` 读的那个登记表。 */
internal fun seedModelRecord(context: Application, name: String = "jvm-fake.gguf"): LocalModelRecord {    val record = LocalModelRecord("content://jvm/${name}", name, 4096, "a".repeat(64), false)
    val directory = File(context.filesDir, "local-models").apply { mkdirs() }
    File(directory, "registry.json").writeText(
        JSONArray().put(
            JSONObject().put("uri", record.uri).put("name", record.name).put("size", record.size)
                .put("sha256", record.sha256).put("copied", record.copied),
        ).toString(),
    )
    return record
}

/** 一条连接一个会话的普通 sqlite，和真机上 SQLCipher 会话的样子一致。 */
internal class JdbcHearthSession(url: String) : HearthSession {
    private val connection: Connection = DriverManager.getConnection(url)

    override fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty()) {
            connection.createStatement().use { it.execute(sql) }
        } else {
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    override fun query(sql: String, args: List<Any?>): List<List<Any?>> {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows ->
                val width = rows.metaData.columnCount
                return buildList {
                    while (rows.next()) add(List(width) { column -> rows.getObject(column + 1) })
                }
            }
        }
    }

    override fun close() = connection.close()
}

/**
 * 可控闸门 + 注入的读失败。
 *
 * 停点放在底层 `BEGIN IMMEDIATE` **之前**（和真机夹具一样）：停住的时候不占写锁，其它会话照样能
 * 开库、读历史，测试才能在被停住的那段时间里做别的事。
 */
internal class GatedJvmSessions(private val url: String) {
    private val lock = Any()
    private var hold: Gate? = null
    private var failure: InjectedFailure? = null

    fun holdTransaction(ordinal: Int): Gate = Gate(Stage.Transaction(ordinal)).also { synchronized(lock) { hold = it } }

    fun holdRead(prefix: String): Gate = Gate(Stage.Read(prefix)).also { synchronized(lock) { hold = it } }

    /** 下一次以 [prefix] 开头的读抛一次错：用来摆出「历史读取失败」这一态。 */
    fun failNextRead(prefix: String, message: String) {
        synchronized(lock) { failure = InjectedFailure(prefix, message) }
    }

    fun open(): HearthSession = GatedSession(JdbcHearthSession(url), this)

    internal fun gateTransaction(ordinal: Int): Gate? = synchronized(lock) {
        val current = hold ?: return null
        if (current.stage != Stage.Transaction(ordinal) || !current.claim()) null else current
    }

    internal fun gateRead(sql: String): Gate? = synchronized(lock) {
        val current = hold ?: return null
        val stage = current.stage
        if (stage !is Stage.Read || !sql.startsWith(stage.sqlPrefix) || !current.claim()) null else current
    }

    internal fun failureFor(sql: String): String? = synchronized(lock) {
        val current = failure ?: return null
        if (!sql.startsWith(current.prefix)) return null
        failure = null
        current.message
    }
}

/** 要停住的阶段。 */
internal sealed interface Stage {
    data class Read(val sqlPrefix: String) : Stage

    /** 同一个会话上的第 [ordinal] 个事务（1 是每次开会话都跑的 schema 检查）。 */
    data class Transaction(val ordinal: Int) : Stage
}

/** 一次已经摆好的闸门：等它到点（[await]），再放行（[letGo]）。 */
internal class Gate(val stage: Stage) {
    private val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)

    internal fun claim(): Boolean = claimed.compareAndSet(false, true)

    /** 不阻塞地问一句：它已经停在闸门上了吗（主线程还要继续推进队列，所以不能直接 await）。 */
    fun reached(): Boolean = entered.count == 0L

    fun await(timeoutMs: Long = 20_000) {
        assertTrue("等待「$stage」停下来超时（${timeoutMs}ms）", entered.await(timeoutMs, TimeUnit.MILLISECONDS))
    }

    fun letGo() = release.countDown()

    internal fun stopHere() {
        entered.countDown()
        release.await()
    }
}

private data class InjectedFailure(val prefix: String, val message: String)

private class GatedSession(private val delegate: HearthSession, private val gates: GatedJvmSessions) : HearthSession {
    private var transactions = 0

    override fun exec(sql: String, args: List<Any?>) = delegate.exec(sql, args)

    override fun query(sql: String, args: List<Any?>): List<List<Any?>> {
        gates.failureFor(sql)?.let { throw IllegalStateException(it) }
        gates.gateRead(sql)?.stopHere()
        return delegate.query(sql, args)
    }

    override fun <T> transaction(block: () -> T): T {
        transactions++
        gates.gateTransaction(transactions)?.stopHere()
        return delegate.transaction(block)
    }

    override fun close() = delegate.close()
}

/**
 * 本地引擎的假实现：顶在真机上 JNI 引擎所在的那个边界上。
 *
 * - [loadedModel] 有值就是「模型已经加载好」，页面会直接跳到生成，不调 [load]；
 * - [holdNextLoad] 让下一次加载挂住，[stop] 会把它放行并且**照常加载成功**（native 最坏的样子）；
 * - [tokens] 是这一轮会吐出来的正文。
 */
internal class FakeLocalEngine : LocalEngine {
    private val loaded = MutableStateFlow<ModelInfo?>(null)
    override val loadedModel: StateFlow<ModelInfo?> = loaded.asStateFlow()
    override val info: ModelInfo get() = checkNotNull(loaded.value) { "请先加载模型" }
    override var lastStats: GenerationStats? = null

    var tokens: List<String> = listOf("好")
    val generates = AtomicInteger()
    val stops = AtomicInteger()
    val loadStarted = CountDownLatch(1)
    private var loadGate: CompletableDeferred<Unit>? = null

    fun holdNextLoad() {
        loadGate = CompletableDeferred()
    }

    fun preload(record: LocalModelRecord) {
        loaded.value = record.info
    }

    override suspend fun load(model: ModelInfo, options: EngineOptions, open: () -> ModelFile) {
        loadStarted.countDown()
        loadGate?.await()
        loadGate = null
        loaded.value = model
    }

    override fun stop() {
        stops.incrementAndGet()
        lastStats = GenerationStats(0, 0, 0, 0, stopped = true)
        loadGate?.complete(Unit)
    }

    override fun generate(messages: List<ChatMessage>, parameters: GenerationParameters): Flow<String> = flow {
        generates.incrementAndGet()
        tokens.forEach { emit(it) }
    }
}
