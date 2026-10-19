package com.xavierxia.mycelium.hearth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CharacterStoreTest {
    private val session = memorySession()
    private val store = CharacterStore { session }
    private val log = ConversationLog { session }

    @Test
    fun createEditCopyAndDeleteKeepTheirOwnConversations() {
        val first = store.create(CharacterDraft(name = "老周", personality = "话少", avatarChar = "周"), "2026-10-17T10:00")
        val second = store.create(CharacterDraft(name = "小满", speech = "先笑再说话"), "2026-10-17T11:00")
        log.append(first.id, null, "今天怎么样", "还行。", "本机 · 测试模型", "2026-10-17T12:00")

        store.update(first.id, CharacterDraft(name = "老周", personality = "话更少", avatarChar = "周"), "2026-10-17T13:00")
        val edited = store.character(first.id)!!
        assertEquals("话更少", edited.personality)
        assertEquals("周", edited.avatarChar)
        assertEquals(0.7f, edited.temperature)
        assertEquals(256, edited.maxTokens)

        val copied = store.copy(first.id, "2026-10-17T14:00")
        assertEquals("老周 的副本", copied.name)
        assertEquals("话更少", copied.personality)
        assertEquals("周", copied.avatarChar)
        assertTrue(copied.id != first.id)
        assertEquals(emptyList(), log.conversations(copied.id))
        assertEquals(1, log.conversations(first.id).size)

        store.delete(first.id)
        assertNull(store.character(first.id))
        assertEquals(emptyList(), log.conversations(first.id))
        assertEquals(listOf("老周 的副本", "小满"), store.characters().map { it.name })
        assertEquals(second.name, store.characters().first { it.id == second.id }.name)
    }

    @Test
    fun aBlankNameIsRejectedAndARecentConversationSortsFirst() {
        assertFailsWith<IllegalArgumentException> { store.create(CharacterDraft(name = "  "), "t") }
        val quiet = store.create(CharacterDraft(name = "甲"), "2026-10-17T01:00")
        val late = store.create(CharacterDraft(name = "乙"), "2026-10-17T02:00")
        log.append(quiet.id, null, "早", "早。", "本机 · 测试模型", "2026-10-17T09:00")
        assertEquals(listOf(quiet.id, late.id), store.characters().map { it.id })
    }

    @Test
    fun answersKeepTheirModelAndTheLastAnswerCanBeReplaced() {
        val row = store.create(CharacterDraft(name = "老周"), "t")
        val id = log.append(row.id, null, "在吗", "在。", "本机 · Qwen", "t1")
        assertEquals(null, log.messages(id).first().model)
        assertEquals("本机 · Qwen", log.messages(id).last().model)

        log.replaceLastAnswer(id, "在的。", "本机 · Qwen", "t2")
        assertEquals(listOf("在吗", "在的。"), log.messages(id).map { it.text })
        assertEquals(2, log.messages(id).size)
    }

    @Test
    fun lastActivityFollowsTheNewestConversation() {
        val quiet = store.create(CharacterDraft(name = "甲"), "2026-10-17T01:00")
        val talked = store.create(CharacterDraft(name = "乙"), "2026-10-17T02:00")
        log.append(talked.id, null, "早", "早。", "本机 · 测试模型", "2026-10-17T09:00")
        assertEquals("2026-10-17T01:00", store.lastActivity()[quiet.id])
        assertEquals("2026-10-17T09:00", store.lastActivity()[talked.id])
    }
}