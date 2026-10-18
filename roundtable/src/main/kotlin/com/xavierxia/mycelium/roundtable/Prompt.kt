package com.xavierxia.mycelium.roundtable

import com.xavierxia.mycelium.contract.AnswerMode
import com.xavierxia.mycelium.contract.SearchHit
import com.xavierxia.mycelium.contract.modeRule
import com.xavierxia.mycelium.contract.renderSystemPrompt

data class OfferedCitation(
    val citationId: String,
    val documentKey: String,
    val title: String,
    val excerpt: String,
    val publishedAt: String?,
)

data class ChatPrompt(val system: String, val user: String, val citations: List<OfferedCitation>)

/**
 * Turns this round's hits into the prompt the answer model sees.
 * Citation numbers are assigned here and are the only ids the model may use.
 */
fun buildPrompt(
    template: String,
    rulesJson: String,
    mode: AnswerMode,
    question: String,
    hits: List<SearchHit>,
    history: List<Pair<String, String>>,
): ChatPrompt {
    val citations = hits.mapIndexed { index, hit ->
        OfferedCitation("C${index + 1}", hit.documentKey, hit.title, hit.excerpt, hit.sourcePublishedAt)
    }
    val evidence = if (citations.isEmpty()) {
        "（本轮没有检索到引用）"
    } else {
        citations.joinToString("\n") { citation ->
            val date = citation.publishedAt?.take(10) ?: "无来源日期"
            "${citation.citationId}｜${citation.title}｜$date｜${citation.excerpt}"
        }
    }
    val earlier = if (history.isEmpty()) {
        "（没有更早的对话）"
    } else {
        history.joinToString("\n") { (question, answer) -> "问：$question\n答：$answer" }
    }
    val user = buildString {
        appendLine("conversation_context：")
        appendLine(earlier)
        appendLine("retrieved_evidence：")
        appendLine(evidence)
        append("问题：")
        append(question)
    }
    return ChatPrompt(renderSystemPrompt(template, modeRule(rulesJson, mode)), user, citations)
}
