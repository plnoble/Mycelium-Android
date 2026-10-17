package com.xavierxia.mycelium.roundtable

import android.content.Context
import android.database.Cursor
import com.xavierxia.mycelium.contract.AnswerMode
import com.xavierxia.mycelium.net.AndroidGateway
import com.xavierxia.mycelium.net.GatewayPurpose
import com.xavierxia.mycelium.net.GatewayRequest
import com.xavierxia.mycelium.net.HttpText
import com.xavierxia.mycelium.update.GitHubReleaseClient
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.YearMonth
import java.time.ZoneOffset

/** Roundtable chat as the screens use it: one persona version, one encrypted history. */
class ChatService(context: Context) {
    private val app = context.applicationContext
    private val settings = CloudSettingsStore(app, SecretStore("mycelium-cloud"))
    private val databaseFile = File(app.filesDir, "roundtable/conversations.sqlite")
    private var gateway = gatewayFor(settings.read())

    fun settings(): ModelSettings = settings.read()

    fun keyHint(): String? = settings.keyHint()

    fun trainingPolicy(): String = DeepSeekDefaults.TRAINING_POLICY

    fun privacyPolicyUrl(): String = DeepSeekDefaults.PRIVACY_POLICY_URL

    fun saveSettings(baseUrl: String, model: String, input: Double, output: Double, limit: Double, apiKey: String?) {
        settings.save(baseUrl, model, input, output, limit, apiKey)
        gateway = gatewayFor(settings.read())
    }

    fun audit() = gateway.audit.read()

    fun clearAudit() = gateway.audit.clear()

    fun conversations(personaId: String): List<ConversationSummary> = store().conversations(personaId)

    fun messages(conversationId: String): List<StoredMessage> = store().messages(conversationId)

    fun delete(conversationId: String) = store().delete(conversationId)

    fun previewCost(
        persona: InstalledVersion,
        question: String,
        mode: AnswerMode,
        template: String,
        rulesJson: String,
    ): CostEstimate {
        val current = settings.read()
        return CloudChat(
            model = DeepSeekAnswerModel(gateway.gateway),
            openPack = { SqlCipherPackDatabase(persona.file.path) },
            template = template,
            rulesJson = rulesJson,
            spentThisMonth = { 0.0 },
        ).preview(question, mode, current, emptyList())
    }

    fun ask(
        persona: InstalledVersion,
        conversationId: String?,
        question: String,
        mode: AnswerMode,
        template: String,
        rulesJson: String,
    ): Pair<String, ChatReply> {
        val current = settings.read()
        gateway = gatewayFor(current)
        val history = conversationId?.let { messages(it) }.orEmpty().toTurns()
        val month = YearMonth.now(ZoneOffset.UTC).toString()
        val chat = CloudChat(
            model = DeepSeekAnswerModel(gateway.gateway),
            openPack = { SqlCipherPackDatabase(persona.file.path) },
            template = template,
            rulesJson = rulesJson,
            spentThisMonth = {
                gateway.audit.read()
                    .filter { it.purpose == GatewayPurpose.RoundtableChat && it.at.startsWith(month) }
                    .sumOf { it.estimatedCostUsd }
            },
        )
        val reply = chat.ask(question, mode, current, history)
        if (reply is ChatReply.Shown) {
            val citations = JSONArray()
            reply.citations.forEach { card ->
                citations.put(
                    JSONObject()
                        .put("id", card.citationId)
                        .put("title", card.title)
                        .put("excerpt", card.excerpt)
                        .put("publishedAt", card.publishedAt ?: JSONObject.NULL),
                )
            }
            val id = store().append(
                persona.personaId,
                persona.versionId,
                conversationId,
                question,
                reply.rendered,
                citations.toString(),
                reply.provider,
                reply.model,
                java.time.Instant.now().toString(),
            )
            return id to reply
        }
        return (conversationId ?: "") to reply
    }

    /** Update checks use the same gateway. Parsing stays in [GitHubReleaseClient]. */
    fun releaseSource(): GitHubReleaseClient = GitHubReleaseClient { url, headers, timeout ->
        val response = gateway.gateway.send(
            GatewayRequest(
                url = url,
                purpose = GatewayPurpose.Update,
                headers = headers,
                connectTimeoutMs = timeout,
                readTimeoutMs = timeout,
            ),
        )
        HttpText(response.status, response.body)
    }

    private fun gatewayFor(current: ModelSettings) = AndroidGateway(app, current.baseUrl, current.prices)

    private fun store() = ConversationStore { openEncrypted() }

    private fun openEncrypted(): SqlSession {
        System.loadLibrary("sqlcipher")
        databaseFile.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(
            databaseFile,
            settings.databasePassphrase(),
            null,
            null,
        )
        return SqlCipherSession(database)
    }
}

private fun List<StoredMessage>.toTurns(): List<ChatTurn> {
    val turns = mutableListOf<ChatTurn>()
    var pending: String? = null
    for (message in this) {
        if (message.role == "user") {
            pending = message.text
        } else if (message.role == "assistant" && pending != null) {
            turns += ChatTurn(pending, message.text)
            pending = null
        }
    }
    return turns
}

private class SqlCipherSession(private val database: SQLiteDatabase) : SqlSession {
    override fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty()) database.execSQL(sql) else database.execSQL(sql, args.toTypedArray())
    }

    override fun query(sql: String, args: List<Any?>): List<List<Any?>> {
        val cursor = if (args.isEmpty()) database.rawQuery(sql, emptyArray()) else database.rawQuery(sql, *args.toTypedArray())
        return cursor.use { rows ->
            buildList {
                while (rows.moveToNext()) add(List(rows.columnCount) { column -> rows.valueAt(column) })
            }
        }
    }

    override fun close() = database.close()
}

private fun Cursor.valueAt(column: Int): Any? = when (getType(column)) {
    Cursor.FIELD_TYPE_NULL -> null
    Cursor.FIELD_TYPE_INTEGER -> getLong(column)
    Cursor.FIELD_TYPE_FLOAT -> getDouble(column)
    Cursor.FIELD_TYPE_BLOB -> String(getBlob(column), Charsets.UTF_8)
    else -> getString(column)
}
