package com.xavierxia.mycelium.hearth

import java.util.concurrent.CountDownLatch

/**
 * Wraps a real session and holds **only the write transaction** open, so a test can abandon a turn at
 * the exact moment that transaction has the write lock and its `valid()` check is about to be asked.
 *
 * Every session open runs one schema check in its own transaction before the caller's block
 * ([withSession] calls [ensureHearthTables] first), so the fixture lets the first
 * [schemaTransactions] transactions straight through. Gating *every* transaction — as an earlier
 * version of the abandoned-write fixture did — releases the latch inside that schema check, and then
 * a `valid()` that moved in front of the write transaction still passes the test. The callers assert
 * [transactions], [writes] and [validSawWriteTransaction], so the latch cannot silently move again:
 * if the check leaves the write transaction the abandoned turn gets stored and the test fails.
 *
 * [beforeWrite] is raised inside the write transaction before its block runs (that is where the
 * check and the writes are); [afterCommit] is raised once `COMMIT` has run but before the call hands
 * its result back, which is how the "committed, then stopped" rule is pinned.
 */
internal class GatedWriteSession(
    private val inner: HearthSession,
    private val schemaTransactions: Int = 1,
    private val beforeWrite: CountDownLatch? = null,
    private val releaseBeforeWrite: CountDownLatch? = null,
    private val afterCommit: CountDownLatch? = null,
    private val releaseAfterCommit: CountDownLatch? = null,
) : HearthSession by inner {
    /** How many transactions this session opened; the first [schemaTransactions] are schema checks. */
    var transactions = 0
        private set

    /** INSERT / UPDATE / DELETE statements run by the callers' blocks: the rows actually written. */
    var writes = 0
        private set

    /** The SQL of every statement the callers' blocks ran, in order. */
    val statements = mutableListOf<String>()

    /** How many times the callers asked their `valid()` lambda. */
    var validCalls = 0
        private set

    /** Whether a write transaction was open the last time `valid()` was asked. */
    var validSawWriteTransaction = false
        private set

    private var writeOpen = false

    /** Called from the test's `valid()` lambda so the test can prove where the check ran. */
    fun noteValidCall() {
        validCalls++
        validSawWriteTransaction = writeOpen
    }

    override fun exec(sql: String, args: List<Any?>) {
        statements += sql
        if (isWrite(sql)) writes++
        inner.exec(sql, args)
    }

    override fun <T> transaction(block: () -> T): T {
        transactions++
        if (transactions <= schemaTransactions) return inner.transaction(block)
        writeOpen = true
        beforeWrite?.countDown()
        releaseBeforeWrite?.await()
        val result = try {
            inner.transaction(block)
        } finally {
            writeOpen = false
        }
        afterCommit?.countDown()
        releaseAfterCommit?.await()
        return result
    }

    /** The test owns the real session, so closing through the store must not close it early. */
    override fun close() = Unit

    private fun isWrite(sql: String): Boolean {
        val head = sql.trimStart().take(6).uppercase()
        return head.startsWith("INSERT") || head.startsWith("UPDATE") || head.startsWith("DELETE")
    }
}