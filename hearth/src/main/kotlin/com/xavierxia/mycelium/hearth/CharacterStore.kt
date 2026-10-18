package com.xavierxia.mycelium.hearth

import java.util.UUID

/** Where a character's answers come from. Stored as text so the column stays readable. */
enum class ModelKind {
    /** Follow the global default; until P2-5 exists that is the first registered local model. */
    DEFAULT,
    /** One specific registered local model. */
    LOCAL,
    /** One model of one saved cloud service. */
    CLOUD,
    ;

    val stored: String get() = name.lowercase()

    companion object {
        fun of(value: String): ModelKind = entries.firstOrNull { it.stored == value } ?: DEFAULT
    }
}

data class HearthCharacter(
    val id: String,
    val name: String,
    val avatarChar: String,
    val personality: String,
    val speech: String,
    val background: String,
    val relationship: String,
    val greeting: String,
    val modelKind: ModelKind,
    val modelId: String,
    val modelName: String,
    val serviceId: String,
    val serviceName: String,
    /** The cloud service the user confirmed sending this character's chats to; empty when never asked. */
    val cloudConfirmedService: String,
    val temperature: Float,
    val maxTokens: Int,
    val color: Int,
    val updatedAt: String,
)

data class CharacterDraft(
    val name: String,
    val avatarChar: String = "",
    val personality: String = "",
    val speech: String = "",
    val background: String = "",
    val relationship: String = "",
    val greeting: String = "",
    val modelKind: ModelKind = ModelKind.DEFAULT,
    val modelId: String = "",
    val modelName: String = "",
    val serviceId: String = "",
    val serviceName: String = "",
    val cloudConfirmedService: String = "",
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

    fun create(draft: CharacterDraft, now: String): HearthCharacter = create(UUID.randomUUID().toString(), draft, now, false)

    /**
     * Writes a character under a caller-chosen id. The preset seeder uses a stable id and
     * [ignoreExisting] so a retry after a partial first run cannot create duplicates.
     */
    fun create(id: String, draft: CharacterDraft, now: String, ignoreExisting: Boolean): HearthCharacter =
        withSession(connect) { session ->
            write(session, id, draft, now, insert = true, ignoreExisting = ignoreExisting)
            requireNotNull(session.query("SELECT $COLUMNS FROM characters WHERE id = ?", listOf(id)).singleOrNull()?.let(::character))
        }

    fun update(id: String, draft: CharacterDraft, now: String) = withSession(connect) { session ->
        require(session.query("SELECT id FROM characters WHERE id = ?", listOf(id)).isNotEmpty()) { "这个人物已经删掉了" }
        write(session, id, draft, now, insert = false, ignoreExisting = false)
    }

    fun copy(id: String, now: String): HearthCharacter = withSession(connect) { session ->
        val source = session.query("SELECT $COLUMNS FROM characters WHERE id = ?", listOf(id)).singleOrNull()
            ?: error("这个人物已经删掉了")
        val original = character(source)
        val draft = draftOf(original).copy(name = "${original.name} 的副本")
        val copyId = UUID.randomUUID().toString()
        write(session, copyId, draft, now, insert = true, ignoreExisting = false)
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

    private fun write(session: HearthSession, id: String, draft: CharacterDraft, now: String, insert: Boolean, ignoreExisting: Boolean) {
        val name = draft.name.trim()
        require(name.isNotEmpty()) { "请填写名字" }
        require(name.length <= 40) { "名字不超过 40 个字" }
        require(draft.temperature.isFinite() && draft.temperature in 0f..2f) { "温度须为 0–2" }
        require(draft.maxTokens in 1..8192) { "生成长度须为 1–8192" }
        when (draft.modelKind) {
            ModelKind.LOCAL -> require(draft.modelId.isNotBlank()) { "请选一个本地模型" }
            ModelKind.CLOUD -> require(draft.serviceId.isNotBlank()) { "请选一个云端服务" }
            ModelKind.DEFAULT -> Unit
        }
        val values = listOf(
            name, draft.personality.trim(), draft.speech.trim(), draft.background.trim(),
            draft.relationship.trim(), draft.greeting.trim(), draft.modelId.trim(), draft.modelName.trim(),
            draft.temperature.toString(), draft.maxTokens, draft.color, now, draft.avatarChar.trim().take(1),
            draft.modelKind.stored, draft.serviceId.trim(), draft.serviceName.trim(), draft.cloudConfirmedService.trim(),
        )
        if (insert) {
            val verb = if (ignoreExisting) "INSERT OR IGNORE" else "INSERT"
            session.exec(
                """
                $verb INTO characters(
                    id, name, personality, speech, background, relationship, greeting,
                    model_id, model_name, temperature, max_tokens, color, updated_at, avatar_char,
                    model_kind, service_id, service_name, cloud_confirmed_service
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                listOf(id) + values,
            )
        } else {
            session.exec(
                """
                UPDATE characters SET name = ?, personality = ?, speech = ?, background = ?, relationship = ?,
                    greeting = ?, model_id = ?, model_name = ?, temperature = ?, max_tokens = ?, color = ?,
                    updated_at = ?, avatar_char = ?, model_kind = ?, service_id = ?, service_name = ?,
                    cloud_confirmed_service = ?
                WHERE id = ?
                """.trimIndent(),
                values + id,
            )
        }
    }

    private companion object {
        const val COLUMNS =
            "id, name, personality, speech, background, relationship, greeting, " +
                "model_id, model_name, temperature, max_tokens, color, updated_at, avatar_char, " +
                "model_kind, service_id, service_name, cloud_confirmed_service"
    }
}

internal fun character(row: List<Any?>) = HearthCharacter(
    id = row.stringAt(0),
    name = row.stringAt(1),
    avatarChar = row.stringAt(13),
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
    modelKind = ModelKind.of(row.stringAt(14)),
    serviceId = row.stringAt(15),
    serviceName = row.stringAt(16),
    cloudConfirmedService = row.stringAt(17),
)

/** The editable form of a stored character; used by the editor screen. */
fun draftOf(character: HearthCharacter) = CharacterDraft(
    name = character.name,
    avatarChar = character.avatarChar,
    personality = character.personality,
    speech = character.speech,
    background = character.background,
    relationship = character.relationship,
    greeting = character.greeting,
    modelKind = character.modelKind,
    modelId = character.modelId,
    modelName = character.modelName,
    serviceId = character.serviceId,
    serviceName = character.serviceName,
    cloudConfirmedService = character.cloudConfirmedService,
    temperature = character.temperature,
    maxTokens = character.maxTokens,
    color = character.color,
)

/** Small key/value table that lives next to the characters (used for the one-time preset seeding). */
class HearthMeta(private val connect: () -> HearthSession) {
    fun flag(key: String): String? = withSession(connect) { session ->
        session.query("SELECT value FROM meta WHERE key = ?", listOf(key)).singleOrNull()?.stringAt(0)
    }

    fun setFlag(key: String, value: String) = withSession(connect) { session ->
        session.exec(
            "INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            listOf(key, value),
        )
    }
}
