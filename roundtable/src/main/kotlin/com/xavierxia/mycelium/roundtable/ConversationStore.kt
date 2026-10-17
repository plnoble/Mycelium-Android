package com.xavierxia.mycelium.roundtable

import java.util.UUID

data class ConversationSummary(
    val id: String,
    val versionId: String,
    val title: String,
    val updatedAt: String,
)

data class StoredMessage(
    val role: String,
    val text: String,
    val citationsJson: String,
    val provider: String?,
    val model: String?,
)

/**
 * Conversation history. A conversation keeps the persona version it started with, so a later
 * import does not retarget it.
 */
class ConversationStore(private val connect: () -> SqlSession) {
    fun conversations(personaId: String): List<ConversationSummary> = use { session ->
        session.query(
            "SELECT id, version_id, title, updated_at FROM conversations WHERE persona_id = ? ORDER BY updated_at DESC",
            listOf(personaId),
        ).map { row ->
            ConversationSummary(row.text(0), row.text(1), row.text(2), row.text(3))
        }
    }

    fun versionId(conversationId: String): String? = use { session ->
        session.query("SELECT version_id FROM conversations WHERE id = ?", listOf(conversationId))
            .singleOrNull()?.text(0)
    }

    fun messages(conversationId: String): List<StoredMessage> = use { session ->
        session.query(
            "SELECT role, text, citations_json, provider, model FROM messages WHERE conversation_id = ? ORDER BY id",
            listOf(conversationId),
        ).map { row ->
            StoredMessage(row.text(0), row.text(1), row.text(2), row.textOrNull(3), row.textOrNull(4))
        }
    }

    fun append(
        personaId: String,
        versionId: String,
        conversationId: String?,
        question: String,
        answer: String,
        citationsJson: String,
        provider: String,
        model: String,
        now: String,
    ): String = use { session ->
        val id = conversationId ?: UUID.randomUUID().toString()
        if (conversationId == null) {
            session.exec(
                "INSERT INTO conversations(id, persona_id, version_id, title, updated_at) VALUES (?, ?, ?, ?, ?)",
                listOf(id, personaId, versionId, question.take(40), now),
            )
        } else {
            session.exec(
                "UPDATE conversations SET updated_at = ? WHERE id = ?",
                listOf(now, id),
            )
        }
        session.exec(
            "INSERT INTO messages(conversation_id, role, text, citations_json, provider, model) VALUES (?, ?, ?, ?, ?, ?)",
            listOf(id, "user", question, "[]", null, null),
        )
        session.exec(
            "INSERT INTO messages(conversation_id, role, text, citations_json, provider, model) VALUES (?, ?, ?, ?, ?, ?)",
            listOf(id, "assistant", answer, citationsJson, provider, model),
        )
        id
    }

    fun delete(conversationId: String) = use { session ->
        session.exec("DELETE FROM messages WHERE conversation_id = ?", listOf(conversationId))
        session.exec("DELETE FROM conversations WHERE id = ?", listOf(conversationId))
    }

    private fun <T> use(block: (SqlSession) -> T): T {
        connect().use { session ->
            session.exec(
                """
                CREATE TABLE IF NOT EXISTS conversations (
                    id TEXT PRIMARY KEY,
                    persona_id TEXT NOT NULL,
                    version_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            session.exec(
                """
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    conversation_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    text TEXT NOT NULL,
                    citations_json TEXT NOT NULL,
                    provider TEXT,
                    model TEXT
                )
                """.trimIndent(),
            )
            return block(session)
        }
    }
}

private fun List<Any?>.text(index: Int): String = this[index] as String

private fun List<Any?>.textOrNull(index: Int): String? = this[index] as String?
