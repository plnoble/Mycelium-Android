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
    /**
     * What the user agreed to send this character's chats to: see [cloudTarget]. Empty means never
     * asked, and a different target — another service, or the same service at another address —
     * means asked again before anything leaves the phone.
     */
    val cloudConfirmedTarget: String,
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
    val cloudConfirmedTarget: String = "",
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

    /**
     * Runs [block] as one transaction over a single session. The preset seeder needs the five rows
     * and the "already seeded" marker to land together, so it cannot use one session per row.
     */
    fun <T> withTransaction(block: (HearthSession) -> T): T =
        withSession(connect) { session -> session.transaction { block(session) } }

    /** Inserts under [id] inside a caller-provided session; false when that id is already there. */
    fun createIfAbsent(session: HearthSession, id: String, draft: CharacterDraft, now: String): Boolean {
        if (session.query("SELECT id FROM characters WHERE id = ?", listOf(id)).isNotEmpty()) return false
        write(session, id, draft, now, insert = true, ignoreExisting = false)
        return true
    }

    fun update(id: String, draft: CharacterDraft, now: String) = withSession(connect) { session ->
        require(session.query("SELECT id FROM characters WHERE id = ?", listOf(id)).isNotEmpty()) { "这个人物已经删掉了" }
        write(session, id, draft, now, insert = false, ignoreExisting = false)
    }

    /** A copy is a new character: it never inherits the cloud agreement of the one it came from. */
    fun copy(id: String, now: String): HearthCharacter = withSession(connect) { session ->
        val source = session.query("SELECT $COLUMNS FROM characters WHERE id = ?", listOf(id)).singleOrNull()
            ?: error("这个人物已经删掉了")
        val original = character(source)
        val draft = draftOf(original).copy(name = "${original.name} 的副本", cloudConfirmedTarget = "")
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
            draft.modelKind.stored, draft.serviceId.trim(), draft.serviceName.trim(), draft.cloudConfirmedTarget.trim(),
        )
        if (insert) {
            val verb = if (ignoreExisting) "INSERT OR IGNORE" else "INSERT"
            session.exec(
                """
                $verb INTO characters(
                    id, name, personality, speech, background, relationship, greeting,
                    model_id, model_name, temperature, max_tokens, color, updated_at, avatar_char,
                    model_kind, service_id, service_name, cloud_confirmed_target
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
                    cloud_confirmed_target = ?
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
                "model_kind, service_id, service_name, cloud_confirmed_target"
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
    cloudConfirmedTarget = row.stringAt(17),
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
    cloudConfirmedTarget = character.cloudConfirmedTarget,
    temperature = character.temperature,
    maxTokens = character.maxTokens,
    color = character.color,
)

/**
 * The identity of a cloud receiver: the saved service plus its normalized endpoint. Only edits that
 * really move the data somewhere else (another service, another host, another port or path) change
 * it, so tidying up a trailing slash does not ask the user again.
 */
fun cloudTarget(serviceId: String, baseUrl: String): String = "$serviceId|${normalizeEndpoint(baseUrl)}"

/** Lower-case scheme and host, default port and trailing slashes dropped. No network parsing here. */
fun normalizeEndpoint(baseUrl: String): String {
    val value = baseUrl.trim()
    val schemeEnd = value.indexOf("://")
    val scheme = if (schemeEnd > 0) value.substring(0, schemeEnd).lowercase() else "https"
    val rest = (if (schemeEnd > 0) value.substring(schemeEnd + 3) else value).trimEnd('/')
    val slash = rest.indexOf('/')
    val authority = if (slash >= 0) rest.substring(0, slash) else rest
    val path = if (slash >= 0) rest.substring(slash).trimEnd('/') else ""
    val host = authority.substringBefore(':').lowercase()
    val port = authority.substringAfter(':', "")
    val defaultPort = (scheme == "https" && port == "443") || (scheme == "http" && port == "80")
    val keptPort = if (port.isEmpty() || defaultPort) "" else ":$port"
    return "$scheme://$host$keptPort$path"
}

/** Small key/value table that lives next to the characters (preset marker, schema version). */
class HearthMeta(private val connect: () -> HearthSession) {
    fun flag(key: String): String? = withSession(connect) { session -> flag(session, key) }

    fun setFlag(key: String, value: String) = withSession(connect) { session -> setFlag(session, key, value) }

    /** Reads the flag inside a caller-provided session, so it can share one transaction. */
    fun flag(session: HearthSession, key: String): String? =
        session.query("SELECT value FROM meta WHERE key = ?", listOf(key)).singleOrNull()?.stringAt(0)

    /** Writes the flag inside a caller-provided session, so it can share one transaction. */
    fun setFlag(session: HearthSession, key: String, value: String) = session.exec(
        "INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        listOf(key, value),
    )
}
