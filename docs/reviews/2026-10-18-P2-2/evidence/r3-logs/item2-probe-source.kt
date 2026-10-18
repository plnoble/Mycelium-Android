package com.xavierxia.mycelium.hearth

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * TEMPORARY r3 discriminative harness — **not** part of the delivered test suite and never committed.
 *
 * The archived device test for review item 2
 * (`app/src/androidTest/.../HearthNavigationDuringTurnDeviceTest.aQuestionSentWhileAnotherConversationIsStillLoadingIsRefusedAndNeverRedirected`,
 * lines 304-318) drives `HearthViewModel` directly and only needs a session that can be held still.
 * It cannot run here (no device, and `HearthDatabase` needs the SQLCipher .so), so this file repeats
 * its two assertions on a Robolectric JVM with a sqlite-jdbc session and a read gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class R3Item2NavProbeTest {

    /** One connection, so every call is serialised: the view model opens a session per call. */
    private class Jdbc(url: String) : HearthSession {
        private val connection: Connection = DriverManager.getConnection(url)
        private val lock = ReentrantLock(true)

        override fun exec(sql: String, args: List<Any?>) {
            lock.withLock {
                if (args.isEmpty()) {
                    connection.createStatement().use { it.execute(sql) }
                } else {
                    connection.prepareStatement(sql).use { statement ->
                        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                        statement.executeUpdate()
                    }
                }
            }
        }

        override fun query(sql: String, args: List<Any?>): List<List<Any?>> = lock.withLock {
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rows ->
                    val width = rows.metaData.columnCount
                    buildList { while (rows.next()) add(List(width) { rows.getObject(it + 1) }) }
                }
            }
        }

        override fun <T> transaction(block: () -> T): T = lock.withLock { super.transaction(block) }

        override fun close() = Unit
    }

    /** Same idea as the device fixture: hold the one read that loads a conversation's messages. */
    private class Gate(private val inner: HearthSession) : HearthSession by inner {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)

        @Volatile
        var armed = false

        override fun query(sql: String, args: List<Any?>): List<List<Any?>> {
            if (armed && sql.startsWith("SELECT role, text, model FROM messages")) {
                armed = false
                held.countDown()
                release.await()
            }
            return inner.query(sql, args)
        }

        override fun close() = Unit
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("等待超时（${timeoutMs}ms）：条件一直没成立", condition())
    }

    private class Fixture {
        val gate = Gate(Jdbc("jdbc:sqlite::memory:"))
        val store = CharacterStore { gate }
        val log = ConversationLog { gate }
        val row: HearthCharacter
        val older: String
        val newest: String

        init {
            row = store.create(CharacterDraft(name = "老周", greeting = "在的。"), "t1")
            older = log.append(row.id, null, "旧问题", "旧回答", "本机 · 测试", "2026-10-18T10:00:00")
            newest = log.append(row.id, null, "新问题", "新回答", "本机 · 测试", "2026-10-18T11:00:00")
        }
    }

    /** Opens the character and switches to [Fixture.older], holding that history read half way. */
    private fun openWithHeldHistory(fixture: Fixture): HearthViewModel {
        val model = HearthViewModel(RuntimeEnvironment.getApplication(), { fixture.gate })
        idle()
        idleUntil(20_000) { model.state.value.characters.any { it.id == fixture.row.id } }
        model.openChat(fixture.row)
        idleUntil(20_000) { !model.state.value.historyLoading && model.state.value.openConversation != null }
        assertEquals("默认应该打开最新的一段", fixture.newest, model.state.value.openConversation)

        fixture.gate.armed = true
        model.openConversation(fixture.older)
        assertTrue("历史读取应该已经被停住", fixture.gate.held.await(10, TimeUnit.SECONDS))
        return model
    }

    @Test
    fun theTargetIsFixedAndThePageWaitsAsSoonAsTheSwitchStarts() {
        val fixture = Fixture()
        val model = HearthViewModel(RuntimeEnvironment.getApplication(), { fixture.gate })
        idle()
        idleUntil(20_000) { model.state.value.characters.any { it.id == fixture.row.id } }
        model.openChat(fixture.row)
        idleUntil(20_000) { !model.state.value.historyLoading && model.state.value.openConversation != null }
        assertEquals("默认应该打开最新的一段", fixture.newest, model.state.value.openConversation)

        fixture.gate.armed = true
        model.openConversation(fixture.older)
        // The two assertions of the device test, lines 310-311.
        assertEquals("切换开始时就该把目标定到新的一段", fixture.older, model.state.value.openConversation)
        assertTrue("读取期间页面必须处于历史未就绪状态", model.state.value.historyLoading)

        fixture.gate.release.countDown()
        idleUntil(20_000) { !model.state.value.historyLoading }
        assertTrue("旧对话的历史应该已经上屏", model.state.value.messages.any { it.text.contains("旧问题") })
    }

    @Test
    fun aSendWhileTheHistoryIsStillBeingReadIsRefused() {
        val fixture = Fixture()
        val model = openWithHeldHistory(fixture)

        // The page is still waiting for the history of the conversation the user just opened.
        model.send("读取期间这条不该发出去")
        idle()
        assertNull("历史还没就绪时不该开始任何一轮（notice）", model.state.value.notice)
        assertEquals("历史还没就绪时目标不该被改回上一段对话", fixture.older, model.state.value.openConversation)

        fixture.gate.release.countDown()
        idleUntil(20_000) { !model.state.value.historyLoading }
        assertTrue(
            "被拒的那条一次都不该入库",
            fixture.log.messages(fixture.older).none { it.text.contains("读取期间这条不该发出去") } &&
                fixture.log.messages(fixture.newest).none { it.text.contains("读取期间这条不该发出去") },
        )
    }
}