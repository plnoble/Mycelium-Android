package com.xavierxia.mycelium.hearth

import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.ChatRole
import java.util.UUID

data class HearthConversation(val id: String, val characterId: String, val title: String, val updatedAt: String)
data class HearthMessage(val role: ChatRole, val text: String, val model: String?)

class ConversationLog(private val connect: () -> HearthSession) {
    fun conversations(characterId: String): List<HearthConversation> = withSession(connect) { session ->
        session.query(
            "SELECT id, character_id, title, updated_at FROM conversations WHERE character_id = ? ORDER BY updated_at DESC",
            listOf(characterId),
        ).map { row ->
            HearthConversation(row.stringAt(0), row.stringAt(1), row.stringAt(2), row.stringAt(3))
        }
    }

    fun messages(conversationId: String): List<HearthMessage> = withSession(connect) { session ->
        session.query(
            "SELECT role, text, model FROM messages WHERE conversation_id = ? ORDER BY id",
            listOf(conversationId),
        ).map { row -> HearthMessage(ChatRole.valueOf(row.stringAt(0)), row.stringAt(1), row.textOrNullAt(2)) }
    }

    /** Stores one question and its answer. The answer keeps the model label it was written by. */
    fun append(characterId: String, conversationId: String?, question: String, answer: String, model: String?, now: String): String =
        withSession(connect) { session ->
            val id = conversationId ?: UUID.randomUUID().toString()
            if (conversationId == null) {
                session.exec(
                    "INSERT INTO conversations(id, character_id, title, updated_at) VALUES (?, ?, ?, ?)",
                    listOf(id, characterId, question.trim().take(40), now),
                )
            } else {
                session.exec("UPDATE conversations SET updated_at = ? WHERE id = ?", listOf(now, id))
            }
            session.exec(
                "INSERT INTO messages(conversation_id, role, text, model) VALUES (?, ?, ?, ?)",
                listOf(id, "USER", question, null),
            )
            session.exec(
                "INSERT INTO messages(conversation_id, role, text, model) VALUES (?, ?, ?, ?)",
                listOf(id, "ASSISTANT", answer, model),
            )
            id
        }

    /** Replaces the last assistant message. The question stays. */
    fun replaceLastAnswer(conversationId: String, answer: String, model: String?, now: String) = withSession(connect) { session ->
        val last = session.query(
            "SELECT id, role FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT 1",
            listOf(conversationId),
        ).singleOrNull() ?: error("这段对话还没有回答")
        require(last.stringAt(1) == "ASSISTANT") { "最后一条不是回答" }
        session.exec("UPDATE messages SET text = ?, model = ? WHERE id = ?", listOf(answer, model, last.numberAt(0).toLong()))
        session.exec("UPDATE conversations SET updated_at = ? WHERE id = ?", listOf(now, conversationId))
    }

    fun delete(conversationId: String) = withSession(connect) { session ->
        session.exec("DELETE FROM messages WHERE conversation_id = ?", listOf(conversationId))
        session.exec("DELETE FROM conversations WHERE id = ?", listOf(conversationId))
    }

    fun asChat(messages: List<HearthMessage>): List<ChatMessage> =
        messages.map { ChatMessage(it.role, it.text) }
}