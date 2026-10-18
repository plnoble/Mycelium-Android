package com.xavierxia.mycelium.hearth

import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.ChatRole

/** The turn helpers around one character's stored history. The model itself is called by the host. */
class HearthChat(private val log: ConversationLog) {
    /** The window for a new question. A null [conversationId] starts a new conversation. */
    fun window(character: HearthCharacter, conversationId: String?, question: String, contextTokens: Int): ContextWindow =
        assemblePrompt(character, history(conversationId), question, contextTokens)

    /** The window for redoing the last answer: the last question stays, its old answer is left out. */
    fun regenerationWindow(character: HearthCharacter, conversationId: String, contextTokens: Int): Pair<String, ContextWindow> {
        val messages = log.messages(conversationId)
        require(messages.size >= 2 && messages.last().role == ChatRole.ASSISTANT) { "最后一条不是回答" }
        val question = messages[messages.size - 2]
        require(question.role == ChatRole.USER) { "最后一条回答前面没有提问" }
        return question.text to assemblePrompt(character, log.asChat(messages.dropLast(2)), question.text, contextTokens)
    }

    private fun history(conversationId: String?): List<ChatMessage> =
        if (conversationId == null) emptyList() else log.asChat(log.messages(conversationId))
}