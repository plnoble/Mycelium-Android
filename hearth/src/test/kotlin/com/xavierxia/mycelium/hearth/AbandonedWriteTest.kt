package com.xavierxia.mycelium.hearth

import com.xavierxia.mycelium.model.ChatRole
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A turn may only be written while it is still wanted, and the check has to happen **inside the write
 * transaction** — not merely somewhere inside a session open. Opening the database or waiting for its
 * write lock can take a while, and the user may stop, switch conversation or leave in between.
 *
 * These tests hold that write transaction open at the exact moment and then abandon the turn.
 * [GatedWriteSession] passes the schema-check transaction every session open runs first, so the latch
 * is raised by the transaction that writes. Move `valid()` in front of that transaction and the
 * abandoned turn is stored: [assertNull] (or [assertFalse]) fails, as the recorded discriminative run
 * in the review report shows.
 */
class AbandonedWriteTest {
    @Test
    fun aTurnAbandonedWhileTheWriteLockIsHeldIsNotStored() {
        val inner = memorySession()
        val store = CharacterStore { inner }
        val armed = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var gate: GatedWriteSession
        val log = ConversationLog {
            GatedWriteSession(inner, beforeWrite = armed, releaseBeforeWrite = release).also { gate = it }
        }
        val chat = HearthChat(log)
        val row = store.create(CharacterDraft(name = "老周", personality = "话少"), "t")

        val abandoned = AtomicBoolean(false)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val writing = pool.submit<String?> {
                chat.finish(row, null, "在吗", "在。", "本机 · 测试模型", "t1", HearthMode.IMMERSIVE) {
                    gate.noteValidCall()
                    !abandoned.get()
                }
            }
            assertTrue(armed.await(10, TimeUnit.SECONDS), "写事务应该已经拿到写锁")
            // The user stops / leaves right here, while the turn waits inside the write transaction.
            abandoned.set(true)
            release.countDown()
            assertNull(writing.get(20, TimeUnit.SECONDS), "作废的一轮不该被存下来")

            // The latch was raised by the turn's own transaction, and the check ran inside it.
            assertEquals(2, gate.transactions, "一次 schema 检查事务加一次写入事务")
            assertEquals(1, gate.validCalls, "valid() 只该问一次")
            assertTrue(gate.validSawWriteTransaction, "valid() 必须在写入事务内被问到")
            assertEquals(0, gate.writes, "作废的一轮一行都不该写")
        } finally {
            pool.shutdownNow()
            inner.close()
        }

        assertEquals(0, log.conversations(row.id).size, "不该出现对话")
        assertEquals(0, inner.query("SELECT id FROM messages").size, "不该出现任何消息")
    }

    @Test
    fun aTurnThatIsStillWantedIsStoredWholeAfterTheSameWait() {
        val inner = memorySession()
        val store = CharacterStore { inner }
        val armed = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var gate: GatedWriteSession
        val log = ConversationLog {
            GatedWriteSession(inner, beforeWrite = armed, releaseBeforeWrite = release).also { gate = it }
        }
        val chat = HearthChat(log)
        val row = store.create(CharacterDraft(name = "老周"), "t")

        val abandoned = AtomicBoolean(false)
        val pool = Executors.newSingleThreadExecutor()
        var conversationId: String? = null
        try {
            val writing = pool.submit<String?> {
                chat.finish(row, null, "在吗", "在。", "本机 · 测试模型", "t1", HearthMode.IMMERSIVE) {
                    gate.noteValidCall()
                    !abandoned.get()
                }
            }
            assertTrue(armed.await(10, TimeUnit.SECONDS), "写事务应该已经拿到写锁")
            release.countDown()
            conversationId = writing.get(20, TimeUnit.SECONDS)
            assertTrue(conversationId != null, "没被放弃的一轮应该存下来")

            assertEquals(2, gate.transactions, "一次 schema 检查事务加一次写入事务")
            assertEquals(1, gate.validCalls)
            assertTrue(gate.validSawWriteTransaction, "valid() 必须在写入事务内被问到")
            assertEquals(3, gate.writes, "一条对话加两条消息，多一行都不该写")
        } finally {
            pool.shutdownNow()
        }

        val id = requireNotNull(conversationId)
        assertEquals(
            listOf(
                HearthMessage(ChatRole.USER, "在吗", null),
                HearthMessage(ChatRole.ASSISTANT, "在。", "本机 · 测试模型"),
            ),
            log.messages(id),
            "问题和回答都要原样存下来，来源标签也不能少",
        )
        assertEquals(listOf(HearthConversation(id, row.id, "在吗", "t1")), log.conversations(row.id))
        inner.close()
    }

    @Test
    fun redoingAnAbandonedAnswerLeavesTheOldOneAndItsTimeInPlace() {
        val inner = memorySession()
        val store = CharacterStore { inner }
        val plain = ConversationLog { inner }
        val row = store.create(CharacterDraft(name = "老周"), "t")
        val id = plain.append(row.id, null, "在吗", "旧回答", "本机 · 旧模型", "t1")

        val armed = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var gate: GatedWriteSession
        val chat = HearthChat(
            ConversationLog {
                GatedWriteSession(inner, beforeWrite = armed, releaseBeforeWrite = release).also { gate = it }
            },
        )
        val abandoned = AtomicBoolean(false)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val redoing = pool.submit<Boolean> {
                chat.redo(id, "新回答", "本机 · 新模型", "t2", HearthMode.IMMERSIVE) {
                    gate.noteValidCall()
                    !abandoned.get()
                }
            }
            assertTrue(armed.await(10, TimeUnit.SECONDS), "写事务应该已经拿到写锁")
            abandoned.set(true)
            release.countDown()
            assertFalse(redoing.get(20, TimeUnit.SECONDS), "作废的重做不该算成功")

            assertEquals(2, gate.transactions, "一次 schema 检查事务加一次写入事务")
            assertEquals(1, gate.validCalls)
            assertTrue(gate.validSawWriteTransaction, "valid() 必须在写入事务内被问到")
            assertEquals(0, gate.writes, "作废的重做一行都不该写")
        } finally {
            pool.shutdownNow()
        }

        assertEquals(
            listOf(
                HearthMessage(ChatRole.USER, "在吗", null),
                HearthMessage(ChatRole.ASSISTANT, "旧回答", "本机 · 旧模型"),
            ),
            plain.messages(id),
            "旧回答和它的来源标签都要原样留着",
        )
        assertEquals(listOf(HearthConversation(id, row.id, "在吗", "t1")), plain.conversations(row.id), "时间戳也不该动")
        inner.close()
    }

    @Test
    fun aRedoThatIsStillWantedReplacesOnlyTheAnswer() {
        val inner = memorySession()
        val store = CharacterStore { inner }
        val plain = ConversationLog { inner }
        val row = store.create(CharacterDraft(name = "老周"), "t")
        val id = plain.append(row.id, null, "在吗", "旧回答", "本机 · 旧模型", "t1")

        val armed = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var gate: GatedWriteSession
        val chat = HearthChat(
            ConversationLog {
                GatedWriteSession(inner, beforeWrite = armed, releaseBeforeWrite = release).also { gate = it }
            },
        )
        val abandoned = AtomicBoolean(false)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val redoing = pool.submit<Boolean> {
                chat.redo(id, "新回答", "本机 · 新模型", "t2", HearthMode.IMMERSIVE) {
                    gate.noteValidCall()
                    !abandoned.get()
                }
            }
            assertTrue(armed.await(10, TimeUnit.SECONDS), "写事务应该已经拿到写锁")
            release.countDown()
            assertTrue(redoing.get(20, TimeUnit.SECONDS), "没被放弃的重做应该算成功")

            assertEquals(2, gate.transactions, "一次 schema 检查事务加一次写入事务")
            assertEquals(1, gate.validCalls)
            assertTrue(gate.validSawWriteTransaction, "valid() 必须在写入事务内被问到")
            assertEquals(2, gate.writes, "只改消息和对话时间两条")
        } finally {
            pool.shutdownNow()
        }

        assertEquals(
            listOf(
                HearthMessage(ChatRole.USER, "在吗", null),
                HearthMessage(ChatRole.ASSISTANT, "新回答", "本机 · 新模型"),
            ),
            plain.messages(id),
            "只换回答本身，问题不动",
        )
        assertEquals(listOf(HearthConversation(id, row.id, "在吗", "t2")), plain.conversations(row.id))
        inner.close()
    }

    /**
     * The other side of the boundary: once `COMMIT` has run the turn is stored, and a stop that lands
     * while the call is still returning must not take it back. The commit is held open here, the turn
     * is abandoned right after it, and the call still hands back the conversation id.
     */
    @Test
    fun aTurnCommittedBeforeTheCallReturnsIsKept() {
        val inner = memorySession()
        val store = CharacterStore { inner }
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var gate: GatedWriteSession
        val log = ConversationLog {
            GatedWriteSession(inner, afterCommit = committed, releaseAfterCommit = release).also { gate = it }
        }
        val chat = HearthChat(log)
        val row = store.create(CharacterDraft(name = "老周"), "t")

        val abandoned = AtomicBoolean(false)
        val pool = Executors.newSingleThreadExecutor()
        var conversationId: String? = null
        try {
            val writing = pool.submit<String?> {
                chat.finish(row, null, "在吗", "在。", "本机 · 测试模型", "t1", HearthMode.IMMERSIVE) {
                    gate.noteValidCall()
                    !abandoned.get()
                }
            }
            assertTrue(committed.await(10, TimeUnit.SECONDS), "写事务应该已经提交")
            abandoned.set(true)
            release.countDown()
            conversationId = writing.get(20, TimeUnit.SECONDS)
            assertTrue(conversationId != null, "事务已经提交，这一轮就算数")
            assertEquals(1, gate.validCalls, "提交之后不该再问一次 valid()")
            assertEquals(3, gate.writes)
        } finally {
            pool.shutdownNow()
        }

        val id = requireNotNull(conversationId)
        assertEquals(
            listOf(
                HearthMessage(ChatRole.USER, "在吗", null),
                HearthMessage(ChatRole.ASSISTANT, "在。", "本机 · 测试模型"),
            ),
            log.messages(id),
            "提交了就以提交为准，消息要留在库里",
        )
        assertEquals(1, log.conversations(row.id).size)
        inner.close()
    }
}