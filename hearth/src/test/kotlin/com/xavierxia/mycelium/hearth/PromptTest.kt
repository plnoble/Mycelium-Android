package com.xavierxia.mycelium.hearth

import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptTest {
    @Test
    fun emptyFieldsAreOmittedAndFilledFieldsAreAllPresent() {
        val empty = systemPrompt(character("老周"))
        assertTrue(empty.contains("你是「老周」"))
        assertFalse(empty.contains("性格"))
        assertFalse(empty.contains("关系"))

        val full = systemPrompt(
            character("老周").copy(
                personality = "寡言",
                speech = "短句",
                background = "退休木工",
                relationship = "邻居",
                greeting = "坐。",
            ),
        )
        listOf("性格：寡言", "说话方式：短句", "背景：退休木工", "和对方的关系：邻居", "你的开场白是：坐。")
            .forEach { assertTrue(full.contains(it), full) }
    }

    @Test
    fun truncationDropsTheOldestTurnAndKeepsTheCharacterSheetAndTheQuestion() {
        val row = character("老周")
        val history = listOf(
            ChatMessage(ChatRole.USER, "第一轮问题非常非常长"),
            ChatMessage(ChatRole.ASSISTANT, "第一轮回答非常非常长"),
            ChatMessage(ChatRole.USER, "第二"),
            ChatMessage(ChatRole.ASSISTANT, "二"),
        )
        // 留出的空间只够最近一轮加这一问。
        val window = assemblePrompt(row, history, "第三问", estimateTokens(systemPrompt(row)) + 40)
        assertEquals(1, window.droppedTurns)
        assertEquals(ChatRole.SYSTEM, window.messages.first().role)
        assertTrue(window.messages.first().content.contains("老周"))
        assertFalse(window.messages.any { it.content.contains("第一轮") })
        assertTrue(window.messages.any { it.content == "第二" })
        assertEquals(ChatRole.USER, window.messages.last().role)
        assertEquals("第三问", window.messages.last().content)
        assertEquals(4, window.messages.size)
    }

    @Test
    fun aDanglingQuestionInHistoryIsNotCountedAsAFinishedTurn() {
        val window = assemblePrompt(
            character("老周"),
            listOf(ChatMessage(ChatRole.USER, "还没回答")),
            "新问题",
            256,
        )
        assertEquals(0, window.droppedTurns)
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER), window.messages.map { it.role })
        assertEquals("新问题", window.messages.last().content)
    }

    @Test
    fun generationParametersComeFromTheCharacter() {
        val parameters = character("老周").copy(temperature = 0.3f, maxTokens = 512).parameters()
        assertEquals(0.3f, parameters.temperature)
        assertEquals(512, parameters.maxTokens)
    }

    private fun character(name: String) = CharacterStore { memorySession() }
        .create(CharacterDraft(name = name), "t")

    @Test
    fun theCacheKeyCarriesCharacterModeConversationAndModel() {
        val row = character("老周")
        val base = localPromptCacheKey(row, HearthMode.IMMERSIVE, "conv-1", "temp-1", "model-a")

        assertEquals(
            localPromptCacheKey(row, HearthMode.IMMERSIVE, "conv-1", "temp-1", "model-a"),
            base,
            "同一人物、同一会话、同一模式、同一模型的键必须稳定",
        )
        assertEquals(
            base,
            localPromptCacheKey(row, HearthMode.IMMERSIVE, "conv-1", "temp-9", "model-a"),
            "已经有会话 ID 时，临时会话的身份不影响键",
        )
        listOf(
            "换人物" to localPromptCacheKey(row.copy(id = "another"), HearthMode.IMMERSIVE, "conv-1", "temp-1", "model-a"),
            "换模式" to localPromptCacheKey(row, HearthMode.TEST, "conv-1", "temp-1", "model-a"),
            "换会话" to localPromptCacheKey(row, HearthMode.IMMERSIVE, "conv-2", "temp-1", "model-a"),
            "换模型" to localPromptCacheKey(row, HearthMode.IMMERSIVE, "conv-1", "temp-1", "model-b"),
        ).forEach { (what, other) -> assertFalse(base == other, "$what 必须换键") }
    }

    @Test
    fun twoTemporaryConversationsNeverShareAKey() {
        val row = character("老周")
        val first = localPromptCacheKey(row, HearthMode.TEST, null, "temp-1", "model-a")
        val againInTheSameOne = localPromptCacheKey(row, HearthMode.TEST, null, "temp-1", "model-a")
        val second = localPromptCacheKey(row, HearthMode.TEST, null, "temp-2", "model-a")

        assertEquals(first, againInTheSameOne, "同一段临时会话内稳定")
        assertFalse(first == second, "两段还没落库的会话不能共用一个键（方案审必修 3）")
    }
}