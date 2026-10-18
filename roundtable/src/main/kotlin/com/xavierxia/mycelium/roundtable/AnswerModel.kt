package com.xavierxia.mycelium.roundtable

import com.xavierxia.mycelium.contract.AnswerMode
import com.xavierxia.mycelium.contract.AnswerOutcome
import com.xavierxia.mycelium.contract.PackDatabase
import com.xavierxia.mycelium.contract.searchPack
import com.xavierxia.mycelium.contract.validateAnswer
import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.ChatModel
import com.xavierxia.mycelium.model.ChatRole
import com.xavierxia.mycelium.model.GenerationParameters
import com.xavierxia.mycelium.model.ModelInfo
import com.xavierxia.mycelium.model.ModelLocation
import com.xavierxia.mycelium.net.GatewayPurpose
import com.xavierxia.mycelium.net.GatewayRequest
import com.xavierxia.mycelium.net.OutboundGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.json.JSONArray
import org.json.JSONObject

data class ChatTurn(val question: String, val answer: String)

data class CitationCard(
    val citationId: String,
    val documentKey: String,
    val title: String,
    val excerpt: String,
    val publishedAt: String?,
)

sealed class ChatReply {
    data class Shown(
        val rendered: String,
        val refusal: Boolean,
        val citations: List<CitationCard>,
        val provider: String,
        val model: String,
    ) : ChatReply()

    data class Rejected(val message: String, val reason: String) : ChatReply()
}

/**
 * The cloud answer model, the same seam the on-device engine implements: :model-api's ChatModel.
 * Not streaming, so the whole body is emitted as one delta. Retrieval, prompting, validation and
 * storage stay outside this class.
 *
 * [purpose] is what the audit log records. The hearth space passes its own purpose (and the test
 * mode's, which keeps the body out of the log); the default keeps the roundtable behaviour.
 */
class CloudChatModel(
    private val gateway: OutboundGateway,
    private val serviceName: String,
    private val baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    private val purpose: GatewayPurpose = GatewayPurpose.RoundtableChat,
    private val readTimeoutMs: Int = 60_000,
) : ChatModel {
    override val info = ModelInfo(
        id = model,
        displayName = "$serviceName · $model",
        location = ModelLocation.CLOUD,
    )

    /** One request, no stream: there is nothing in flight to abort between deltas. */
    override fun stop() = Unit

    override fun generate(messages: List<ChatMessage>, parameters: GenerationParameters): Flow<String> = flow {
        val key = apiKey ?: throw IllegalStateException("这个服务还没有填写 API key")
        val list = JSONArray()
        messages.forEach { message ->
            list.put(
                JSONObject()
                    .put("role", message.role.name.lowercase())
                    .put("content", message.content),
            )
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", list)
            .put("response_format", JSONObject().put("type", "json_object"))
            // Official thinking-mode guide: thinking is on by default. extra_body carries this object.
            .put("thinking", JSONObject().put("type", "disabled"))
        val response = gateway.send(
            GatewayRequest(
                url = baseUrl.trimEnd('/') + "/chat/completions",
                purpose = purpose,
                method = "POST",
                headers = mapOf(
                    "Authorization" to "Bearer $key",
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                body = payload.toString(),
                readTimeoutMs = readTimeoutMs,
            ),
        )
        if (response.status !in 200..299) {
            throw IllegalStateException(failureMessage(response.status))
        }
        val json = JSONObject(response.body)
        val content = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        emit(content)
    }

    private fun failureMessage(status: Int): String = when (status) {
        401 -> "云端拒绝了密钥（401）"
        429 -> "云端限流（429），这次没有回答"
        else -> "云端返回 HTTP $status"
    }
}

/**
 * Retrieval, prompt building, one model call and validation. The model call goes through the
 * shared ChatModel seam, so a later on-device implementation changes nothing here.
 */
class CloudChat(
    private val model: ChatModel,
    private val providerName: String,
    private val modelName: String,
    private val openPack: () -> PackDatabase,
    private val template: String,
    private val rulesJson: String,
) {
    suspend fun ask(
        question: String,
        mode: AnswerMode,
        history: List<ChatTurn>,
    ): ChatReply {
        val prepared = prepare(question, mode, history)
        val content = model.generate(
            listOf(
                ChatMessage(ChatRole.SYSTEM, prepared.system),
                ChatMessage(ChatRole.USER, prepared.user),
            ),
        ).toList().joinToString("")
        return when (
            val outcome = validateAnswer(
                content,
                mode,
                prepared.citations.map { it.citationId }.toSet(),
            )
        ) {
            is AnswerOutcome.Rejected -> ChatReply.Rejected("这次回答不合格，没有显示", outcome.message)
            is AnswerOutcome.Accepted -> ChatReply.Shown(
                rendered = outcome.rendered,
                refusal = outcome.refusalReason != null,
                citations = prepared.citations
                    .filter { it.citationId in outcome.usedCitationIds }
                    .map { CitationCard(it.citationId, it.documentKey, it.title, it.excerpt, it.publishedAt) },
                provider = providerName,
                model = modelName,
            )
        }
    }

    private fun prepare(question: String, mode: AnswerMode, history: List<ChatTurn>): ChatPrompt {
        val hits = openPack().let { database ->
            searchPack(database, question, limit = 8, includeInactive = false).hits
        }
        return buildPrompt(template, rulesJson, mode, question, hits, history.map { it.question to it.answer })
    }
}
