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
 */
class GatedHearthSessions(private val database: HearthDatabase) {
    private val lock = Any()
    private var current: HearthStageHold? = null

    /** 下一次出现 [stage] 时把它停住；返回的门闩用来等它真的到点、再放行。 */
    fun hold(stage: HearthStage): HearthStageHold =
        HearthStageHold(stage).also { hold -> synchronized(lock) { current = hold } }

    fun open(): HearthSession = GatedHearthSession(database.session(), this)

    internal fun gateRead(sql: String): HearthStageHold? = synchronized(lock) {
        val hold = current ?: return null
        val stage = hold.stage
        if (stage !is HearthStage.Read || !sql.startsWith(stage.sqlPrefix) || !hold.claim()) return null
        hold
    }

    internal fun gateTransaction(ordinal: Int): HearthStageHold? = synchronized(lock) {
        val hold = current ?: return null
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

/** 一次已经摆好的闸门：等它到点（[await]），再放行（[letGo]）。 */
class HearthStageHold(val stage: HearthStage) {
    private val claimed = AtomicBoolean(false)
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)

    internal fun claim(): Boolean = claimed.compareAndSet(false, true)

    /** 等到真的停下来；超时就是失败，而不是让测试挂住。 */
    fun await(timeoutMs: Long = 20_000) {
        assertTrue("等待「$stage」停下来超时（${timeoutMs}ms）", entered.await(timeoutMs, TimeUnit.MILLISECONDS))
    }

    /** 放行；放行之后这道闸门不会再拦任何东西。 */
    fun letGo() = release.countDown()

    internal fun stopHere() {
        entered.countDown()
        release.await()
    }
}

/** 除被停住的那一刻外，行为和生产里的会话完全一样。 */
private class GatedHearthSession(private val delegate: HearthSession, private val gates: GatedHearthSessions) : HearthSession {
    private var transactions = 0

    override fun exec(sql: String, args: List<Any?>) = delegate.exec(sql, args)

    override fun query(sql: String, args: List<Any?>): List<List<Any?>> {
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