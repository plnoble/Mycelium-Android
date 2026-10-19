package com.xavierxia.mycelium.hearth

import com.xavierxia.mycelium.model.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HearthChatTest {
    private val session = memorySession()
    private val store = CharacterStore { session }
    private val log = ConversationLog { session }
    private val chat = HearthChat(log)

    @Test
    fun theWindowCarriesStoredTurnsAndTheQuestionLast() {
        val row = store.create(CharacterDraft(name = "老周", personality = "话少"), "t")
        val id = log.append(row.id, null, "今天怎么样", "还行。", "本机 · 测试模型", "t1")
        val window = chat.window(row, id, "明天呢", 256)
        assertEquals(
            listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER),
            window.messages.map { it.role },
        )
        assertTrue(window.messages.any { it.content == "还行。" })
        assertEquals("明天呢", window.messages.last().content)
        assertEquals(0, window.droppedTurns)
    }

    @Test
    fun aNewConversationWindowHasOnlyTheSheetAndTheQuestion() {
        val row = store.create(CharacterDraft(name = "老周"), "t")
        val window = chat.window(row, null, "第一次说话", 256)
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER), window.messages.map { it.role })
    }

    @Test
    fun regeneratingKeepsTheQuestionAndLeavesOutTheOldAnswer() {
        val row = store.create(CharacterDraft(name = "老周"), "t")
        val id = log.append(row.id, null, "今天怎么样", "旧回答", "本机 · 测试模型", "t1")
        val (question, window) = chat.regenerationWindow(row, id, 256)
        assertEquals("今天怎么样", question)
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER), window.messages.map { it.role })
        assertEquals("今天怎么样", window.messages.last().content)
        assertTrue(window.messages.none { it.content == "旧回答" })
    }
}