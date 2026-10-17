package com.xavierxia.mycelium.roundtable

import com.xavierxia.mycelium.contract.AnswerMode
import com.xavierxia.mycelium.contract.AnswerOutcome
import com.xavierxia.mycelium.contract.searchPack
import com.xavierxia.mycelium.contract.validateAnswer
import com.xavierxia.mycelium.net.GatewayPurpose
import com.xavierxia.mycelium.net.GatewayRequest
import com.xavierxia.mycelium.net.OutboundGateway
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

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
        val estimatedCostUsd: Double,
    ) : ChatReply()

    data class Rejected(val message: String, val reason: String) : ChatReply()
}

/**
 * The one seam a later on-device model plugs into. Retrieval, prompting, validation and storage
 * stay outside it.
 */
fun interface AnswerModel {
    fun complete(system: String, user: String, settings: ModelSettings): ModelCompletion
}

data class ModelCompletion(val content: String, val promptTokens: Int, val completionTokens: Int)

class CloudChat(
    private val model: AnswerModel,
    private val openPack: () -> com.xavierxia.mycelium.contract.PackDatabase,
    private val template: String,
    private val rulesJson: String,
    private val spentThisMonth: () -> Double,
) {
    fun ask(
        question: String,
        mode: AnswerMode,
        settings: ModelSettings,
        history: List<ChatTurn>,
    ): ChatReply {
        val prepared = prepare(question, mode, history)
        val estimate = estimateCost(prepared.system.length + prepared.user.length, settings.prices)
        requireWithinBudget(spentThisMonth(), estimate, settings.monthlyLimitUsd)
        val completion = model.complete(prepared.system, prepared.user, settings)
        return when (
            val outcome = validateAnswer(
                completion.content,
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
                provider = "DeepSeek",
                model = settings.model,
                estimatedCostUsd = actualCost(completion.promptTokens, completion.completionTokens, settings.prices),
            )
        }
    }

    /** Cost of the prompt that would actually be sent, including retrieval. Does not call the model. */
    fun preview(question: String, mode: AnswerMode, settings: ModelSettings, history: List<ChatTurn>): CostEstimate {
        val prepared = prepare(question, mode, history)
        return estimateCost(prepared.system.length + prepared.user.length, settings.prices)
    }

    private fun prepare(question: String, mode: AnswerMode, history: List<ChatTurn>): ChatPrompt {
        val hits = openPack().let { database ->
            searchPack(database, question, limit = 8, includeInactive = false).hits
        }
        return buildPrompt(template, rulesJson, mode, question, hits, history.map { it.question to it.answer })
    }
}

/** DeepSeek's OpenAI-compatible chat completion, reached only through the gateway. */
class DeepSeekAnswerModel(
    private val gateway: OutboundGateway,
    private val readTimeoutMs: Int = 60_000,
) : AnswerModel {
    override fun complete(system: String, user: String, settings: ModelSettings): ModelCompletion {
        val key = settings.apiKey ?: throw IllegalStateException("还没有填写 API key")
        val endpoint = settings.baseUrl.trimEnd('/') + "/chat/completions"
        val payload = JSONObject()
            .put("model", settings.model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
            .put("response_format", JSONObject().put("type", "json_object"))
            // Official thinking-mode guide: thinking is on by default. extra_body carries this object.
            .put("thinking", JSONObject().put("type", "disabled"))
        val response = gateway.send(
            GatewayRequest(
                url = endpoint,
                purpose = GatewayPurpose.RoundtableChat,
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
        val usage = json.optJSONObject("usage")
        return ModelCompletion(
            content,
            usage?.optInt("prompt_tokens") ?: 0,
            usage?.optInt("completion_tokens") ?: 0,
        )
    }

    private fun failureMessage(status: Int): String = when (status) {
        401 -> "云端拒绝了密钥（401）"
        429 -> "云端限流（429），这次没有回答"
        else -> "云端返回 HTTP $status"
    }
}

/** The host the configured base URL names, used when pricing a completed call. */
fun modelHost(baseUrl: String): String = URI(baseUrl).host.orEmpty()
