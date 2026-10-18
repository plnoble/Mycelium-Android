package com.xavierxia.mycelium.hearth

import java.util.UUID

data class HearthCharacter(
    val id: String,
    val name: String,
    val personality: String,
    val speech: String,
    val background: String,
    val relationship: String,
    val greeting: String,
    val modelId: String,
    val modelName: String,
    val temperature: Float,
    val maxTokens: Int,
    val color: Int,
    val updatedAt: String,
)

data class CharacterDraft(
    val name: String,
    val personality: String = "",
    val speech: String = "",
    val background: String = "",
    val relationship: String = "",
    val greeting: String = "",
    val modelId: String = "",
    val modelName: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 256,
    val color: Int = 0xFF7EB6A4.toInt(),
)

/**
 * Characters and their conversations. The caller supplies the session (SQLCipher on the phone);
 * this module never opens a file or a network socket.
 */
class CharacterStore(private val connect: () -> HearthSession) {
    fun characters(): List<HearthCharacter> = withSession(connect) { session ->
        session.query(
            """
            SELECT $COLUMNS
            FROM characters c
            ORDER BY COALESCE((SELECT MAX(updated_at) FROM conversations WHERE character_id = c.id), c.updated_at) DESC
            """.trimIndent(),
        ).map(::character)
    }

    fun character(id: String): HearthCharacter? = withSession(connect) { session ->
        session.query("SELECT $COLUMNS FROM characters WHERE id = ?", listOf(id)).singleOrNull()?.let(::character)
    }

    fun create(draft: CharacterDraft, now: String): HearthCharacter = withSession(connect) { session ->
        val id = UUID.randomUUID().toString()
        write(session, id, draft, now, insert = true)
        requireNotNull(session.query("SELECT $COLUMNS FROM characters WHERE id = ?", listOf(id)).singleOrNull()?.let(::character))
    }

    fun update(id: String, draft: CharacterDraft, now: String) = withSession(connect) { session ->
        require(session.query("SELECT id FROM characters WHERE id = ?", listOf(id)).isNotEmpty()) { "这个人物已经删掉了" }
        write(session, id, draft, now, insert = false)
    }

    fun copy(id: String, now: String): HearthCharacter = withSession(connect) { session ->
        val source = session.query("SELECT $COLUMNS FROM characters WHERE id = ?", listOf(id)).singleOrNull()
            ?: error("这个人物已经删掉了")
        val original = character(source)
        val draft = draftOf(original).copy(name = "${original.name} 的副本")
        val copyId = UUID.randomUUID().toString()
        write(session, copyId, draft, now, insert = true)
        requireNotNull(session.query("SELECT $COLUMNS FROM characters WHERE id = ?", listOf(copyId)).singleOrNull()?.let(::character))
    }

    /** Deletes the character and every conversation that belongs to them. */
    fun delete(id: String) = withSession(connect) { session ->
        val conversations = session.query("SELECT id FROM conversations WHERE character_id = ?", listOf(id))
        conversations.forEach { row ->
            session.exec("DELETE FROM messages WHERE conversation_id = ?", listOf(row.stringAt(0)))
        }
        session.exec("DELETE FROM conversations WHERE character_id = ?", listOf(id))
        session.exec("DELETE FROM characters WHERE id = ?", listOf(id))
    }

    /** When each character was last active: the newest conversation time, or its own updated_at. */
    fun lastActivity(): Map<String, String> = withSession(connect) { session ->
        session.query(
            """
            SELECT c.id, COALESCE(
                (SELECT MAX(updated_at) FROM conversations WHERE character_id = c.id),
                c.updated_at
            )
            FROM characters c
            """.trimIndent(),
        ).associate { row -> row.stringAt(0) to row.stringAt(1) }
    }

    private fun write(session: HearthSession, id: String, draft: CharacterDraft, now: String, insert: Boolean) {
        val name = draft.name.trim()
        require(name.isNotEmpty()) { "请填写名字" }
        require(name.length <= 40) { "名字不超过 40 个字" }
        require(draft.temperature.isFinite() && draft.temperature in 0f..2f) { "温度须为 0–2" }
        require(draft.maxTokens in 1..8192) { "生成长度须为 1–8192" }
        val values = listOf(
            name, draft.personality.trim(), draft.speech.trim(), draft.background.trim(),
            draft.relationship.trim(), draft.greeting.trim(), draft.modelId.trim(), draft.modelName.trim(),
            draft.temperature.toString(), draft.maxTokens, draft.color, now,
        )
        if (insert) {
            session.exec(
                """
                INSERT INTO characters(
                    id, name, personality, speech, background, relationship, greeting,
                    model_id, model_name, temperature, max_tokens, color, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                listOf(id) + values,
            )
        } else {
            session.exec(
                """
                UPDATE characters SET name = ?, personality = ?, speech = ?, background = ?, relationship = ?,
                    greeting = ?, model_id = ?, model_name = ?, temperature = ?, max_tokens = ?, color = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent(),
                values + id,
            )
        }
    }

    private companion object {
        const val COLUMNS =
            "id, name, personality, speech, background, relationship, greeting, " +
                "model_id, model_name, temperature, max_tokens, color, updated_at"
    }
}

internal fun character(row: List<Any?>) = HearthCharacter(
    id = row.stringAt(0),
    name = row.stringAt(1),
    personality = row.stringAt(2),
    speech = row.stringAt(3),
    background = row.stringAt(4),
    relationship = row.stringAt(5),
    greeting = row.stringAt(6),
    modelId = row.stringAt(7),
    modelName = row.stringAt(8),
    temperature = row.stringAt(9).toFloat(),
    maxTokens = row.numberAt(10).toInt(),
    color = row.numberAt(11).toInt(),
    updatedAt = row.stringAt(12),
)

internal fun draftOf(character: HearthCharacter) = CharacterDraft(
    name = character.name,
    personality = character.personality,
    speech = character.speech,
    background = character.background,
    relationship = character.relationship,
    greeting = character.greeting,
    modelId = character.modelId,
    modelName = character.modelName,
    temperature = character.temperature,
    maxTokens = character.maxTokens,
    color = character.color,
)