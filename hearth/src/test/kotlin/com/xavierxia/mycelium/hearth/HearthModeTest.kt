package com.xavierxia.mycelium.hearth

import com.xavierxia.mycelium.model.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Test mode must leave the database untouched; immersive mode is the one that writes. */
class HearthModeTest {
    private val session = memorySession()
    private val store = CharacterStore { session }
    private val log = ConversationLog { session }
    private val chat = HearthChat(log)
    private val character = store.create(CharacterDraft(name = "老周", personality = "话少"), "2026-10-18T10:00")

    private fun rows(table: String): Int = session.query("SELECT COUNT(*) FROM $table").single().numberAt(0).toInt()

    @Test
    fun testModeWritesNothingAtAll() {
        val before = rows("messages") to rows("conversations")
        assertNull(chat.finish(character, null, "在吗", "在。", "本机 · 测试", "2026-10-18T10:01", HearthMode.TEST))
        assertEquals(before, rows("messages") to rows("conversations"))
        assertFalse(chat.redo("whatever", "改一下", "本机 · 测试", "2026-10-18T10:02", HearthMode.TEST))
        assertEquals(before, rows("messages") to rows("conversations"))
        assertEquals(emptyList(), log.conversations(character.id))
    }

    @Test
    fun immersiveModeStoresTheTurn() {
        val id = chat.finish(character, null, "在吗", "在。", "本机 · 测试", "2026-10-18T10:03", HearthMode.IMMERSIVE)
        assertEquals(2, rows("messages"))
        assertEquals(1, rows("conversations"))
        assertEquals(listOf("在吗", "在。"), log.messages(requireNotNull(id)).map { it.text })
        assertEquals("本机 · 测试", log.messages(requireNotNull(id)).last().model)
    }

    @Test
    fun testModeWindowIgnoresWhatIsStored() {
        chat.finish(character, null, "库里的问题", "库里的回答", "本机 · 测试", "2026-10-18T10:04", HearthMode.IMMERSIVE)
        val stored = log.conversations(character.id).first().id
        // A finished turn on screen: the question being asked now is passed separately.
        val onScreen = listOf(
            HearthMessage(ChatRole.USER, "屏幕上的问题", null),
            HearthMessage(ChatRole.ASSISTANT, "屏幕上的回答", "本机 · 测试"),
        )
        val window = chat.window(character, stored, "新问题", 2048, HearthMode.TEST, onScreen)
        val texts = window.messages.map { it.content }
        assertTrue(texts.any { it.contains("屏幕上的问题") }, "测试模式应该看得到屏幕上的轮次")
        assertFalse(texts.any { it.contains("库里的问题") }, "测试模式不该读库里的历史")
        assertEquals("新问题", texts.last())
    }

    @Test
    fun immersiveModeWindowReadsTheStoredHistory() {
        val id = chat.finish(character, null, "库里的问题", "库里的回答", "本机 · 测试", "2026-10-18T10:05", HearthMode.IMMERSIVE)
        val window = chat.window(character, id, "新问题", 2048, HearthMode.IMMERSIVE, emptyList())
        val texts = window.messages.map { it.content }
        assertTrue(texts.any { it.contains("库里的问题") })
        assertEquals("新问题", texts.last())
    }

    @Test
    fun redoingFromTheScreenKeepsTheLastQuestion() {
        val onScreen = listOf(
            HearthMessage(ChatRole.USER, "第一个问题", null),
            HearthMessage(ChatRole.ASSISTANT, "第一个回答", "本机 · 测试"),
            HearthMessage(ChatRole.USER, "第二个问题", null),
            HearthMessage(ChatRole.ASSISTANT, "第二个回答", "本机 · 测试"),
        )
        val prepared = requireNotNull(chat.regenerationWindow(character, onScreen, 2048))
        assertEquals("第二个问题", prepared.first)
        val texts = prepared.second.messages.map { it.content }
        assertFalse(texts.any { it.contains("第二个回答") }, "重新生成时旧回答要拿掉")
        assertTrue(texts.any { it.contains("第一个回答") }, "更早的轮次要留着")
    }
}
