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

/** Roundtable chat as the screens use it: one persona version, one encrypted history. */
class ChatService(context: Context) {
    private val app = context.applicationContext
    private val settings = CloudSettingsStore(app, KeystoreSecretBox("mycelium-cloud"))
    private val databaseFile = File(app.filesDir, "roundtable/conversations.sqlite")
    private var gateway = gatewayFor(settings)

    fun services(): List<CloudService> = settings.services()

    fun addService(id: String?, name: String, baseUrl: String, apiKey: String?): String {
        val serviceId = settings.addService(id, name, baseUrl, apiKey)
        gateway = gatewayFor(settings)
        return serviceId
    }

    fun removeService(serviceId: String) {
        settings.removeService(serviceId)
        gateway = gatewayFor(settings)
    }

    fun keyHint(serviceId: String): String? = settings.keyHint(serviceId)

    fun models(serviceId: String): List<String> = settings.models(serviceId)

    fun saveModels(serviceId: String, models: List<String>) = settings.saveModels(serviceId, models)

    fun selection(): CloudSelection? = settings.selection()

    fun rememberSelection(serviceId: String, model: String) = settings.rememberSelection(serviceId, model)

    fun trainingPolicy(): String = DeepSeekDefaults.TRAINING_POLICY

    fun privacyPolicyUrl(): String = DeepSeekDefaults.PRIVACY_POLICY_URL

    /** Pulls the model list through the gateway. The key stays out of the audit log. */
    fun fetchModels(serviceId: String): List<String> {
        val service = settings.services().firstOrNull { it.id == serviceId }
            ?: throw ModelListError("这个服务已经删掉了")
        return pullModels(
            gateway = gatewayFor(settings).gateway,
            baseUrl = service.baseUrl,
            apiKey = settings.apiKey(serviceId),
        )
    }

    fun audit() = gateway.audit.read()

    fun clearAudit() = gateway.audit.clear()

    fun conversations(personaId: String): List<ConversationSummary> = store().conversations(personaId)

    fun messages(conversationId: String): List<StoredMessage> = store().messages(conversationId)

    fun delete(conversationId: String) = store().delete(conversationId)

    suspend fun ask(
        persona: InstalledVersion,
        conversationId: String?,
        question: String,
        mode: AnswerMode,
        template: String,
        rulesJson: String,
    ): Pair<String, ChatReply> {
        val selected = settings.selection() ?: throw IllegalStateException("请先在「云端模型」里选一个模型")
        val service = settings.services().firstOrNull { it.id == selected.serviceId }
            ?: throw IllegalStateException("选中的服务已经删掉了，请重新选一个模型")
        val currentGateway = gatewayFor(settings)
        gateway = currentGateway
        val history = conversationId?.let { messages(it) }.orEmpty().toTurns()
        val chat = CloudChat(
            model = CloudChatModel(
                gateway = currentGateway.gateway,
                serviceName = service.name,
                baseUrl = service.baseUrl,
                apiKey = settings.apiKey(service.id),
                model = selected.model,
            ),
            providerName = service.name,
            modelName = selected.model,
            openPack = { SqlCipherPackDatabase(persona.file.path) },
            template = template,
            rulesJson = rulesJson,
        )
        val reply = chat.ask(question, mode, history)
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

    private fun gatewayFor(store: CloudSettingsStore) = AndroidGateway(app, store.allBaseUrls())

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
