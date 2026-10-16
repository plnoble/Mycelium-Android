package com.xavierxia.mycelium.contract

/**
 * Answer protocol v0.1.0 (`answer/VALIDATION.md`). Repair parsing is intentionally absent:
 * consumers must not depend on it.
 */
const val MAX_STATEMENTS = 12
private const val MAX_STATEMENT_CHARS = 600
private const val MAX_ANSWER_CHARS = 6_000
private const val MAX_REFUSAL_CHARS = 1_000

enum class AnswerMode(val id: String) {
    StrictEvidence("strict_evidence"),
    ThoughtInference("thought_inference"),
    RoleExpression("role_expression"),
    ;

    companion object {
        fun fromId(id: String): AnswerMode =
            entries.firstOrNull { it.id == id } ?: throw AnswerRejected("未知回答模式：$id", "bad_mode")
    }
}

data class AnswerStatement(val text: String, val kind: String, val citationIds: List<String>)

sealed class AnswerOutcome {
    data class Accepted(
        val statements: List<AnswerStatement>,
        val refusalReason: String?,
        val rendered: String,
        val usedCitationIds: List<String>,
    ) : AnswerOutcome()

    data class Rejected(val error: String, val message: String) : AnswerOutcome()
}

class AnswerRejected(message: String, val error: String) : Exception(message)

/** Fills `{mode_rule}` and `{max_statements}` in the contract system-prompt template. */
fun renderSystemPrompt(template: String, modeRule: String): String =
    template.replace("{mode_rule}", modeRule).replace("{max_statements}", MAX_STATEMENTS.toString())

fun modeRule(rulesJson: String, mode: AnswerMode): String =
    parseJson(rulesJson)[mode.id]?.asTextOrNull() ?: throw AnswerRejected("模式规则缺少 ${mode.id}", "bad_mode")

/** Validates one model output and renders it. A rejection is a result, not an exception. */
fun validateAnswer(outputText: String, mode: AnswerMode, offeredCitationIds: Set<String>): AnswerOutcome {
    val node = parseOutput(outputText) ?: return AnswerOutcome.Rejected("invalid_json", "人物回答不是合法 JSON")
    val statementsNode = node["statements"]
    if (statementsNode == null || !statementsNode.isArray) {
        return AnswerOutcome.Rejected("invalid_json", "人物回答不是合法 JSON")
    }
    if (statementsNode.size() > MAX_STATEMENTS) {
        return AnswerOutcome.Rejected("over_statement_limit", "人物回答超过 $MAX_STATEMENTS 条陈述上限")
    }
    val refusalNode = node["refusal_reason"]
    if (refusalNode != null && !refusalNode.isNull && refusalNode.asTextOrNull() == null) {
        return AnswerOutcome.Rejected("invalid_json", "人物回答不是合法 JSON")
    }
    val refusal = refusalNode?.asTextOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    if (statementsNode.isEmpty()) {
        if (refusal == null) {
            return AnswerOutcome.Rejected("empty_without_refusal", "人物回答没有陈述，也没有说明拒答原因")
        }
        if (refusal.codePointCount() > MAX_REFUSAL_CHARS) {
            return AnswerOutcome.Rejected("refusal_too_long", "人物回答的拒答原因超过 1000 字")
        }
        return AnswerOutcome.Accepted(emptyList(), refusal, refusal, emptyList())
    }
    if (refusal != null) {
        return AnswerOutcome.Rejected("statements_and_refusal", "人物回答不能同时给出陈述和拒答原因")
    }

    val statements = mutableListOf<AnswerStatement>()
    val rendered = mutableListOf<String>()
    val used = linkedSetOf<String>()
    var totalChars = 0
    statementsNode.elements().forEachIndexed { index, statement ->
        when (val checked = checkStatement(statement, index + 1, mode, offeredCitationIds)) {
            is AnswerOutcome.Rejected -> return checked
            is AnswerOutcome.Accepted -> {
                val one = checked.statements.single()
                totalChars += one.text.codePointCount()
                if (totalChars > MAX_ANSWER_CHARS) {
                    return AnswerOutcome.Rejected("answer_too_long", "人物回答总长度超过 6000 字")
                }
                statements += one
                rendered += checked.rendered
                used += one.citationIds
            }
        }
    }
    return AnswerOutcome.Accepted(statements, null, rendered.joinToString("\n\n"), used.toList())
}

private fun checkStatement(
    statement: JsonValue,
    index: Int,
    mode: AnswerMode,
    offered: Set<String>,
): AnswerOutcome {
    if (!statement.isObject) return AnswerOutcome.Rejected("invalid_json", "人物回答不是合法 JSON")
    val textNode = statement["text"]
    val kindNode = statement["kind"]
    val citationNode = statement["citation_ids"]
    if (textNode?.asTextOrNull() == null ||
        kindNode?.asTextOrNull() == null ||
        citationNode?.stringElementsOrNull() == null
    ) {
        return AnswerOutcome.Rejected("invalid_json", "人物回答不是合法 JSON")
    }
    val text = textNode.asTextOrNull()!!.trim()
    if (text.isEmpty() || text.codePointCount() > MAX_STATEMENT_CHARS) {
        return AnswerOutcome.Rejected("statement_too_long", "人物回答第 $index 条陈述为空或超过字数上限")
    }
    if (text.contains("[C") || text.contains("【C")) {
        return AnswerOutcome.Rejected("self_written_citation", "人物回答第 $index 条自行写入了引用标记")
    }
    val kind = kindNode.asTextOrNull()!!
    if (kind != "evidence" && kind != "inference") {
        return AnswerOutcome.Rejected("bad_kind", "人物回答第 $index 条 kind 非法")
    }
    if (mode == AnswerMode.StrictEvidence && kind == "inference") {
        return AnswerOutcome.Rejected("inference_in_strict_mode", "严格证据模式出现了推演陈述")
    }
    val citationIds = citationNode.stringElementsOrNull()!!
        .map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet().toList()
    if (citationIds.isEmpty()) {
        return AnswerOutcome.Rejected("no_citation", "人物回答第 $index 条没有引用")
    }
    citationIds.firstOrNull { it !in offered }?.let { unknown ->
        return AnswerOutcome.Rejected("unknown_citation", "人物回答引用了未提供的证据：$unknown")
    }
    val prefix = if (kind == "inference") "【推演】" else ""
    val marks = citationIds.joinToString("") { "[$it]" }
    return AnswerOutcome.Accepted(
        listOf(AnswerStatement(text, kind, citationIds)),
        null,
        "$prefix$text $marks",
        citationIds,
    )
}

private fun parseOutput(outputText: String): JsonValue? {
    val stripped = outputText.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    return try {
        parseJson(stripped)
    } catch (_: Exception) {
        null
    }
}

private fun String.codePointCount(): Int = codePointCount(0, length)
