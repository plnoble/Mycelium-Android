package com.xavierxia.mycelium.contract

import org.junit.jupiter.api.Assertions.assertEquals
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
                case["query"]!!.asText(),
                case["limit"]!!.asInt(),
                case["include_inactive"]!!.asBoolean(),
            )
            val id = case["id"]!!.asText()
            assertEquals(case["strategy"]!!.asText(), result.strategy, id)
            assertEquals(case["expected"]!!.elements().map { it.asText() }, result.hits.map { it.documentKey }, id)
        }
    }
}
