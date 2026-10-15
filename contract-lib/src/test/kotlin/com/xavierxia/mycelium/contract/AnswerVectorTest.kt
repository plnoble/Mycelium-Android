package com.xavierxia.mycelium.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.readText

class AnswerVectorTest {
    @Test
    fun everyVectorMatchesExactly() {
        val files = ContractFiles(contractRoot())
        val vectors = files.path("answer/vectors").toFile().listFiles { file -> file.extension == "json" }
            .orEmpty().sortedBy { it.name }
        assertEquals(13, vectors.size)
        vectors.forEach { file ->
            val case = parseJson(file.readText())
            val input = case["input"]!!
            val expected = case["expected"]!!
            val outcome = validateAnswer(
                input["output_text"]!!.asText(),
                AnswerMode.fromId(input["mode"]!!.asText()),
                input["offered_citation_ids"]!!.elements().map { it.asText() }.toSet(),
            )
            val id = case["id"]!!.asText()
            when (outcome) {
                is AnswerOutcome.Accepted -> {
                    assertTrue(expected["accepted"]!!.asBoolean(), id)
                    assertEquals(expected["refusal_reason"]?.nullableText(), outcome.refusalReason, id)
                    val rendered = if (outcome.refusalReason != null) null else outcome.rendered
                    assertEquals(expected["rendered"]?.nullableText(), rendered, id)
                }
                is AnswerOutcome.Rejected -> {
                    assertEquals(false, expected["accepted"]!!.asBoolean(), id)
                    assertEquals(expected["error"]!!.asText(), outcome.error, "$id: ${outcome.message}")
                }
            }
        }
    }

    @Test
    fun systemPromptUsesContractTemplate() {
        val files = ContractFiles(contractRoot())
        val rendered = renderSystemPrompt(
            files.text("answer/system-prompt.zh.txt"),
            modeRule(files.text("answer/mode-rules.zh.json"), AnswerMode.ThoughtInference),
        )
        assertTrue(rendered.contains("允许 evidence 和 inference"))
        assertTrue(rendered.contains("最多 12 条 statements"))
        assertTrue(!rendered.contains("{mode_rule}") && !rendered.contains("{max_statements}"))
    }
}

private fun JsonValue.nullableText(): String? = if (isNull) null else asText()
