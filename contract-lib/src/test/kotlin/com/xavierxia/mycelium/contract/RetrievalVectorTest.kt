package com.xavierxia.mycelium.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.readText

class RetrievalVectorTest {
    @Test
    fun everyVectorMatchesExactly() {
        val files = ContractFiles(contractRoot())
        val database = SqliteJdbcPackDatabase(files.sampleDatabase().toString())
        val cases = parseJson(files.path("retrieval/vectors.json").readText())["cases"]!!
        assertEquals(7, cases.size())
        cases.elements().forEach { case ->
            val result = searchPack(
                database,
                case["query"]!!.asTextOrNull()!!,
                case["limit"]!!.asInt(),
                case["include_inactive"]!!.asBoolean(),
            )
            val id = case["id"]!!.asTextOrNull()!!
            assertEquals(case["strategy"]!!.asTextOrNull(), result.strategy, id)
            assertEquals(case["expected"]!!.elements().map { it.asTextOrNull() }, result.hits.map { it.documentKey }, id)
            result.hits.forEach { hit ->
                if (hit.documentKind == "theme") {
                    assertEquals(null, hit.sourcePublishedAt, id)
                } else {
                    assertTrue(hit.sourcePublishedAt?.isNotEmpty() == true, "$id ${hit.documentKey}")
                }
            }
        }
    }

    @Test
    fun ideographicSpaceSplitsALongQueryTheSameWayAsAsciiSpace() {
        val files = ContractFiles(contractRoot())
        val database = SqliteJdbcPackDatabase(files.sampleDatabase().toString())
        val ascii = searchPack(database, "记账改 变的是", 10, false)
        val ideographic = searchPack(database, "记账改\u3000变的是", 10, false)
        assertEquals(ascii.hits.map { it.documentKey }, ideographic.hits.map { it.documentKey })
        assertEquals(ascii.hits.map { it.relevance }, ideographic.hits.map { it.relevance })
        assertTrue(ascii.hits.any { it.documentKey == "claim:lc-claim-record-1" })

        val noBreak = searchPack(database, "记账改\u00A0变的是", 10, false)
        assertEquals(ascii.hits.map { it.documentKey }, noBreak.hits.map { it.documentKey })
        assertEquals(ascii.hits.map { it.relevance }, noBreak.hits.map { it.relevance })

        val unitSeparator = searchPack(database, "记账改\u001F变的是", 10, false)
        val primary = { hits: List<SearchHit> ->
            hits.filter { it.relevance >= 1000.0 }.map { it.documentKey }
        }
        assertTrue(primary(ascii.hits).contains("claim:lc-claim-record-1"))
        assertEquals(emptyList<String>(), primary(unitSeparator.hits))
    }

    @Test
    fun searchTermsCountSupplementaryCharactersAsOneScalar() {
        assertEquals(listOf("野家", "𠮷野"), searchTerms("𠮷野家"))
        val lowered = "𝐀B".lowercase()
        assertEquals(listOf(lowered), searchTerms("𝐀B"))
        assertEquals(2, lowered.codePointCount(0, lowered.length))
    }
}
