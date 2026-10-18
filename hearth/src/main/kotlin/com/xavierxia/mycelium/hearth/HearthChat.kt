package com.xavierxia.mycelium.hearth

import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.ChatRole

/** How the conversation page behaves. Test mode is the no-trace mode. */
enum class HearthMode { IMMERSIVE, TEST }

/**
 * The turn helpers around one character's history. The model itself is called by the host.
 *
 * The mode rules live here, not in the screen: test mode never reads the stored history and never
 * writes to it, so a JVM test can prove it by counting rows.
 */
class HearthChat(private val log: ConversationLog) {
    /** The window for a new question. A null [conversationId] starts a new conversation. */
    fun window(character: HearthCharacter, conversationId: String?, question: String, contextTokens: Int): ContextWindow =
        assemblePrompt(character, history(conversationId), question, contextTokens)

    /**
     * The window for the page. Test mode ignores what is stored and only sees the turns that are
     * already on screen; immersive mode reads the conversation from the log.
     */
    fun window(
        character: HearthCharacter,
        conversationId: String?,
        question: String,
        contextTokens: Int,
        mode: HearthMode,
        onScreen: List<HearthMessage>,
    ): ContextWindow = when (mode) {
        HearthMode.TEST -> assemblePrompt(character, log.asChat(onScreen), question, contextTokens)
        HearthMode.IMMERSIVE -> window(character, conversationId, question, contextTokens)
    }

    /** The window for redoing the last answer: the last question stays, its old answer is left out. */
    fun regenerationWindow(character: HearthCharacter, conversationId: String, contextTokens: Int): Pair<String, ContextWindow> {
        val messages = log.messages(conversationId)
        require(messages.size >= 2 && messages.last().role == ChatRole.ASSISTANT) { "最后一条不是回答" }
        val question = messages[messages.size - 2]
        require(question.role == ChatRole.USER) { "最后一条回答前面没有提问" }
        return question.text to assemblePrompt(character, log.asChat(messages.dropLast(2)), question.text, contextTokens)
    }

    /** Redoing the last answer from the turns on screen (test mode). Null when there is nothing to redo. */
    fun regenerationWindow(character: HearthCharacter, onScreen: List<HearthMessage>, contextTokens: Int): Pair<String, ContextWindow>? {
        if (onScreen.lastOrNull()?.role != ChatRole.ASSISTANT) return null
        val lastQuestion = onScreen.indexOfLast { it.role == ChatRole.USER }
        if (lastQuestion < 0) return null
        val question = onScreen[lastQuestion]
        return question.text to assemblePrompt(character, log.asChat(onScreen.subList(0, lastQuestion)), question.text, contextTokens)
    }

    /**
     * Stores one finished turn. Test mode writes nothing and returns null, so nothing about it can
     * be found later.
     */
    fun finish(
        character: HearthCharacter,
        conversationId: String?,
        question: String,
        answer: String,
        label: String?,
        now: String,
        mode: HearthMode,
    ): String? = when (mode) {
        HearthMode.TEST -> null
        HearthMode.IMMERSIVE -> log.append(character.id, conversationId, question, answer, label, now)
    }

    /** Replaces the last answer. Test mode writes nothing and returns false. */
    fun redo(conversationId: String, answer: String, label: String?, now: String, mode: HearthMode): Boolean = when (mode) {
        HearthMode.TEST -> false
        HearthMode.IMMERSIVE -> {
            log.replaceLastAnswer(conversationId, answer, label, now)
            true
        }
    }

    private fun history(conversationId: String?): List<ChatMessage> =
        if (conversationId == null) emptyList() else log.asChat(log.messages(conversationId))
}
