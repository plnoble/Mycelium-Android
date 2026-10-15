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
                input["output_text"]!!.asTextOrNull()!!,
                AnswerMode.fromId(input["mode"]!!.asTextOrNull()!!),
                input["offered_citation_ids"]!!.elements().map { it.asTextOrNull()!! }.toSet(),
            )
            val id = case["id"]!!.asTextOrNull()!!
            when (outcome) {
                is AnswerOutcome.Accepted -> {
                    assertTrue(expected["accepted"]!!.asBoolean(), id)
                    assertEquals(expected["refusal_reason"]?.nullableText(), outcome.refusalReason, id)
                    val rendered = if (outcome.refusalReason != null) null else outcome.rendered
                    assertEquals(expected["rendered"]?.nullableText(), rendered, id)
                }
                is AnswerOutcome.Rejected -> {
                    assertEquals(false, expected["accepted"]!!.asBoolean(), id)
                    assertEquals(expected["error"]!!.asTextOrNull(), outcome.error, "$id: ${outcome.message}")
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

    @Test
    fun wrongTypesAndTrailingTextAreInvalidJson() {
        val offered = setOf("C1")
        listOf(
            """{"statements":[{"text":123,"kind":"evidence","citation_ids":["C1"]}],"refusal_reason":null}""",
            """{"statements":[{"text":{"a":1},"kind":"evidence","citation_ids":["C1"]}],"refusal_reason":null}""",
            """{"statements":[],"refusal_reason":123}""",
            """{"statements":[],"refusal_reason":true}""",
            """{"statements":[{"text":"一句","kind":"evidence","citation_ids":["C1"]}],"refusal_reason":null} 以上是回答""",
            """{'statements':[],'refusal_reason':'没有'}""",
            """{statements:[],"refusal_reason":null}""",
            """{"statements":["一句"],"refusal_reason":null}""",
            """{"statements":[{"text":"一句","kind":1,"citation_ids":["C1"]}],"refusal_reason":null}""",
            """{"statements":[{"text":"一句","kind":"evidence","citation_ids":[1]}],"refusal_reason":null}""",
        ).forEach { output ->
            val outcome = validateAnswer(output, AnswerMode.StrictEvidence, offered)
            assertTrue(outcome is AnswerOutcome.Rejected, output)
            assertEquals("invalid_json", (outcome as AnswerOutcome.Rejected).error, output)
        }
    }
}

private fun JsonValue.nullableText(): String? = if (isNull) null else asTextOrNull()
