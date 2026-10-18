package com.xavierxia.mycelium.hearth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A turn may only be written while it is still wanted. The check has to happen *inside* the write
 * transaction, because opening the database or waiting for its write lock can take a while and the
 * user may stop, switch conversation or leave the page in between.
 *
 * These tests hold the transaction open at that exact moment and then abandon the turn: if the
 * check ever moves back in front of the transaction, they fail (the turn would be written).
 */
class AbandonedWriteTest {
    @Test
    fun aTurnAbandonedWhileTheWriteLockIsHeldIsNotStored() {
        val inner = memorySession()
        val store = CharacterStore { inner }
        val armed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val log = ConversationLog { GatedSession(inner, armed, release) }
        val chat = HearthChat(log)
        val row = store.create(CharacterDraft(name = "老周", personality = "话少"), "t")

        val abandoned = AtomicBoolean(false)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val writing = pool.submit<String?> {
                chat.finish(row, null, "在吗", "在。", "本机 · 测试模型", "t1", HearthMode.IMMERSIVE) { !abandoned.get() }
            }
            assertTrue(armed.await(10, TimeUnit.SECONDS), "写事务应该已经拿到写锁")
            // The user stops / leaves right here, while the turn is waiting inside the transaction.
            abandoned.set(true)
            release.countDown()
            assertNull(writing.get(20, TimeUnit.SECONDS), "作废的一轮不该被存下来")
        } finally {
            pool.shutdownNow()
            inner.close()
        }

        assertEquals(0, log.conversations(row.id).size, "不该出现对话")
        assertEquals(0, inner.query("SELECT id FROM messages").size, "不该出现任何消息")
    }

    @Test
    fun aTurnThatIsStillWantedIsStoredAfterTheSameWait() {
        val inner = memorySession()
        val store = CharacterStore { inner }
        val armed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val log = ConversationLog { GatedSession(inner, armed, release) }
        val chat = HearthChat(log)
        val row = store.create(CharacterDraft(name = "老周"), "t")

        val abandoned = AtomicBoolean(false)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val writing = pool.submit<String?> {
                chat.finish(row, null, "在吗", "在。", "本机 · 测试模型", "t1", HearthMode.IMMERSIVE) { !abandoned.get() }
            }
            assertTrue(armed.await(10, TimeUnit.SECONDS), "写事务应该已经拿到写锁")
            release.countDown()
            val conversationId = writing.get(20, TimeUnit.SECONDS)
            assertTrue(conversationId != null, "没被放弃的一轮应该存下来")
            assertEquals(2, log.messages(conversationId).size, "问题和回答一起提交")
        } finally {
            pool.shutdownNow()
            inner.close()
        }
    }

    /**
     * Takes the write lock, tells the test it is inside, waits for the test to decide, then behaves
     * like the real session does.
     */
    private class GatedSession(
        private val inner: HearthSession,
        private val armed: CountDownLatch,
        private val release: CountDownLatch,
    ) : HearthSession by inner {
        override fun <T> transaction(block: () -> T): T {
            inner.exec("BEGIN IMMEDIATE")
            armed.countDown()
            release.await()
            return try {
                val result = block()
                inner.exec("COMMIT")
                result
            } catch (failure: Throwable) {
                runCatching { inner.exec("ROLLBACK") }
                throw failure
            }
        }

        /** The test owns the real session, so closing through the log must not close it early. */
        override fun close() = Unit
    }
}