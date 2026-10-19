package com.xavierxia.mycelium.hearth

import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 真机测试用的一道可控闸门。生产代码自己开 SQLCipher 会话，这里包一层，让测试把「某一次读」或
 * 「某一次普通事务」停住，再按需放行：导航时序因此可以一步一步摆出来，而不是靠 sleep 去碰窗口。
 *
 * 每次开会话都会先跑一次 schema 检查事务，所以调用者自己的写事务是同一个会话上的第 2 个事务。
 *
 * 这里的每一个等待都是有界的，而且都能当同步点用：
 * - [HearthStageHold.await] 等闸门到点；超时把**全部线程栈**打出来再失败，不再出现「挂住没人管」；
 * - [HearthStageHold.awaitCallFinished] 等被停住的这一次调用真的走完（提交/回滚/读到结果），
 *   它就是 sleep 的替代品：这一步回来，这次写库的判定已经做完了；
 * - [HearthStageHold.stopHere] 内部的放行等待也有上限：测试万一漏了放行，只是这一轮照常跑完，
 *   绝不会把 IO 线程永远留在闸门上；
 * - [GatedHearthSessions.releaseAll] 让用例在 finally 里一次放行所有闸门。
 */
class GatedHearthSessions(private val database: HearthDatabase) {
    private val lock = Any()
    private val holds = mutableListOf<HearthStageHold>()

    /** 下一次出现 [stage] 时把它停住；返回的门闩用来等它真的到点、再放行。 */
    fun hold(stage: HearthStage): HearthStageHold =
        HearthStageHold(stage).also { hold -> synchronized(lock) { holds += hold } }

    /** 放行所有摆过的闸门：用例的 finally 里兜底，闸门不会跨用例留着一个停住的线程。 */
    fun releaseAll() = synchronized(lock) { holds.toList() }.forEach { it.letGo() }

    fun open(): HearthSession = GatedHearthSession(database.session(), this)

    internal fun gateRead(sql: String): HearthStageHold? = synchronized(lock) {
        val hold = holds.lastOrNull() ?: return null
        val stage = hold.stage
        if (stage !is HearthStage.Read || !sql.startsWith(stage.sqlPrefix) || !hold.claim()) return null
        hold
    }

    internal fun gateTransaction(ordinal: Int): HearthStageHold? = synchronized(lock) {
        val hold = holds.lastOrNull() ?: return null
        if (hold.stage != HearthStage.Transaction(ordinal) || !hold.claim()) return null
        hold
    }
}

/** 要停住的阶段。 */
sealed interface HearthStage {
    /** 一条以 [sqlPrefix] 开头的 SQL 读。 */
    data class Read(val sqlPrefix: String) : HearthStage

    /** 同一个会话上的第 [ordinal] 个事务（1 是每次开会话都跑的 schema 检查）。 */
    data class Transaction(val ordinal: Int) : HearthStage
}

/** 一次已经摆好的闸门：等它到点（[await]），再放行（[letGo]），再等这次调用走完（[awaitCallFinished]）。 */
class HearthStageHold(val stage: HearthStage) {
    private val claimed = AtomicBoolean(false)
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val finished = CountDownLatch(1)

    /** 到点还没被放行、闸门自己兜底放行了：说明用例漏了 `letGo()`。 */
    @Volatile
    var selfReleased: Boolean = false
        private set

    internal fun claim(): Boolean = claimed.compareAndSet(false, true)

    /** 等到真的停下来；超时就是失败（带全部线程栈），而不是让测试挂住。 */
    fun await(timeoutMs: Long = 20_000) {
        assertTrue(
            "等待「$stage」停下来超时（${timeoutMs}ms）——闸门一直没被碰到。全部线程栈：\n${threadDump()}",
            entered.await(timeoutMs, TimeUnit.MILLISECONDS),
        )
    }

    /**
     * 等这次被停住的调用真的走完：事务已经提交/回滚，或这次读已经返回（抛出也算走完）。
     * 断言「什么都不该发生」时用它当同步点，不要用 sleep。
     */
    fun awaitCallFinished(timeoutMs: Long = 20_000) {
        assertTrue(
            "等待「$stage」这一次调用走完超时（${timeoutMs}ms）。全部线程栈：\n${threadDump()}",
            finished.await(timeoutMs, TimeUnit.MILLISECONDS),
        )
    }

    /** 放行；放行之后这道闸门不会再拦任何东西。 */
    fun letGo() = release.countDown()

    internal fun stopHere() {
        entered.countDown()
        if (!release.await(PARK_LIMIT_MS, TimeUnit.MILLISECONDS)) {
            selfReleased = true
            println("[GatedHearthSessions] 「$stage」停住超过 ${PARK_LIMIT_MS}ms 还没有人放行，闸门自己放行")
        }
    }

    /** 被停住的那次调用已经走完。 */
    internal fun callFinished() = finished.countDown()

    private companion object {
        /** 闸门最多把一个 IO 线程停这么久；到点自己放行，免得用例的疏忽变成进程级的挂死。 */
        const val PARK_LIMIT_MS = 60_000L
    }
}

/** 现场的全部线程栈：等待超时的失败信息里带上它，卡在哪里一眼能看出来。 */
fun threadDump(): String = Thread.getAllStackTraces().entries.joinToString("\n") { (thread, stack) ->
    "\"${thread.name}\" state=${thread.state} daemon=${thread.isDaemon}\n" +
        stack.joinToString("\n") { "    at $it" }
}

/** 除被停住的那一刻外，行为和生产里的会话完全一样。 */
private class GatedHearthSession(private val delegate: HearthSession, private val gates: GatedHearthSessions) : HearthSession {
    private var transactions = 0

    override fun exec(sql: String, args: List<Any?>) = delegate.exec(sql, args)

    override fun query(sql: String, args: List<Any?>): List<List<Any?>> {
        val gate = gates.gateRead(sql) ?: return delegate.query(sql, args)
        gate.stopHere()
        return try {
            delegate.query(sql, args)
        } finally {
            gate.callFinished()
        }
    }

    override fun <T> transaction(block: () -> T): T {
        transactions++
        val gate = gates.gateTransaction(transactions) ?: return delegate.transaction(block)
        gate.stopHere()
        return try {
            delegate.transaction(block)
        } finally {
            gate.callFinished()
        }
    }

    override fun close() = delegate.close()
}
