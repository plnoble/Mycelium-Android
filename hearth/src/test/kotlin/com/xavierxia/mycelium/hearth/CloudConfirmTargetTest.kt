package com.xavierxia.mycelium.hearth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Confirming a cloud receiver is a **narrow** write: it may only touch the confirmation itself, and
 * it has to be decided inside its own write transaction, because the dialog can be dismissed or the
 * page left while the database is still opening or waiting for its write lock.
 *
 * The old path wrote the whole character back from the snapshot the page had made when the question
 * was asked ([CharacterStore.update] with a stale draft). These tests pin both halves of the fix:
 * nothing is written after a cancellation, and the successful write cannot put an old name, speech or
 * model choice back — it does not name those columns at all.
 */
class CloudConfirmTargetTest {
    private val target = cloudTarget("svc-1", "https://api.example/v1")

    @Test
    fun aConfirmationCancelledWhileTheWriteWaitsChangesNothing() {
        val inner = memorySession()
        val store = CharacterStore { inner }
        val row = store.create(CharacterDraft(name = "老周", personality = "话少"), "t1")

        val armed = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var gate: GatedWriteSession
        val gated = CharacterStore {
            GatedWriteSession(inner, beforeWrite = armed, releaseBeforeWrite = release).also { gate = it }
        }

        val cancelled = AtomicBoolean(false)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val writing = pool.submit<Boolean> {
                gated.confirmCloudTarget(row.id, target, "t9") {
                    gate.noteValidCall()
                    !cancelled.get()
                }
            }
            assertTrue(armed.await(10, TimeUnit.SECONDS), "确认的写事务应该已经拿到写锁")
            // The dialog is dismissed / the page is left right here, before the confirmation is written.
            cancelled.set(true)
            release.countDown()
            assertFalse(writing.get(20, TimeUnit.SECONDS), "取消之后的确认不该算数")

            assertEquals(2, gate.transactions, "一次 schema 检查事务加一次写入事务")
            assertEquals(1, gate.validCalls, "valid() 只该问一次")
            assertTrue(gate.validSawWriteTransaction, "valid() 必须在写入事务内被问到")
            assertEquals(0, gate.writes, "取消之后一列都不该改")
        } finally {
            pool.shutdownNow()
        }

        val after = store.character(row.id)!!
        assertEquals("", after.cloudConfirmedTarget, "确认字段不该被写")
        assertEquals("t1", after.updatedAt, "时间戳不该被写")
        assertEquals("老周", after.name)
        assertEquals("话少", after.personality)
        inner.close()
    }

    @Test
    fun aConfirmationOnlyTouchesTheConfirmColumnAndTheTimestamp() {
        val inner = memorySession()
        val store = CharacterStore { inner }
        val row = store.create(
            CharacterDraft(
                name = "老周",
                avatarChar = "周",
                personality = "话少",
                speech = "慢",
                background = "老街坊",
                relationship = "邻居",
                greeting = "早",
                modelKind = ModelKind.LOCAL,
                modelId = "local-1",
                modelName = "本机 · 甲",
                temperature = 0.4f,
                maxTokens = 512,
                color = 0x112233,
            ),
            "t1",
        )
        // What the page held when it asked the question. The user edits the character while the
        // confirmation is up, so that snapshot is now stale — which is exactly what used to be
        // written back over the edit.
        val snapshot = store.character(row.id)!!
        store.update(row.id, draftOf(snapshot).copy(name = "老周改", personality = "话多了", speech = "快"), "t5")
        assertEquals("老周", draftOf(snapshot).name, "页面手里的快照确实是旧名字")

        lateinit var gate: GatedWriteSession
        val gated = CharacterStore { GatedWriteSession(inner).also { gate = it } }
        assertTrue(gated.confirmCloudTarget(row.id, target, "t9"), "正常的确认应当写库")

        val after = store.character(row.id)!!
        assertEquals("老周改", after.name)
        assertEquals("话多了", after.personality)
        assertEquals("快", after.speech)
        assertEquals("周", after.avatarChar)
        assertEquals("老街坊", after.background)
        assertEquals("邻居", after.relationship)
        assertEquals("早", after.greeting)
        assertEquals("local-1", after.modelId)
        assertEquals("本机 · 甲", after.modelName)
        assertEquals(ModelKind.LOCAL, after.modelKind)
        assertEquals(0.4f, after.temperature)
        assertEquals(512, after.maxTokens)
        assertEquals(0x112233, after.color)
        assertEquals(target, after.cloudConfirmedTarget, "确认的目标要原样写下来")
        assertEquals("t9", after.updatedAt, "时间戳要跟着确认走")

        assertEquals(1, gate.writes, "确认只该写一条 UPDATE：${gate.statements}")
        val updates = gate.statements.filter { it.trimStart().startsWith("UPDATE characters") }
        assertEquals(1, updates.size, "确认只该碰 characters 表一次：${gate.statements}")
        val setColumns = updates.single().substringAfter("SET").substringBefore("WHERE").lowercase()
        assertTrue("cloud_confirmed_target" in setColumns, setColumns)
        assertTrue("updated_at" in setColumns, setColumns)
        val untouched = listOf(
            "name", "personality", "speech", "background", "relationship", "greeting",
            "model_id", "model_name", "temperature", "max_tokens", "color", "avatar_char",
            "model_kind", "service_id", "service_name",
        )
        untouched.forEach { column ->
            assertFalse(
                Regex("\\b$column\\b").containsMatchIn(setColumns),
                "确认不该改 $column，实际 SET 的是：$setColumns",
            )
        }
        inner.close()
    }

    @Test
    fun aNullTargetRecordsNoAgreementAtAll() {
        val inner = memorySession()
        val store = CharacterStore { inner }
        val row = store.create(CharacterDraft(name = "老周"), "t1")

        assertTrue(store.confirmCloudTarget(row.id, target, "t9"))
        assertEquals(target, store.character(row.id)!!.cloudConfirmedTarget)
        assertTrue(store.confirmCloudTarget(row.id, null, "t10"), "把同意收回也要算写成功")
        assertEquals("", store.character(row.id)!!.cloudConfirmedTarget, "空目标表示从没同意过")
        assertEquals("t10", store.character(row.id)!!.updatedAt)
        inner.close()
    }

    @Test
    fun confirmingACharacterThatIsGoneWritesNothing() {
        val inner = memorySession()
        val store = CharacterStore { inner }
        store.create(CharacterDraft(name = "老周"), "t1")

        lateinit var gate: GatedWriteSession
        val gated = CharacterStore { GatedWriteSession(inner).also { gate = it } }
        assertFalse(gated.confirmCloudTarget("这个人物不存在", target, "t9"), "人物没了就不能算确认成功")
        assertEquals(0, gate.writes, "人物没了就一行都不该写")
        assertEquals(1, store.characters().size, "别人物还是要好好的")
        inner.close()
    }
}