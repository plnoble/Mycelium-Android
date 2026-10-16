package com.xavierxia.mycelium.roundtable

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xavierxia.mycelium.contract.parseJson
import com.xavierxia.mycelium.contract.searchPack
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the contract retrieval vectors on the device with the SQLCipher build chosen in P0-7. */
@RunWith(AndroidJUnit4::class)
class DeviceRetrievalTest {
    @Test
    fun samplePackMatchesEveryRetrievalVector() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val database = File(target.cacheDir, "synthetic-persona-v1.sqlite")
        context.assets.open("samples/synthetic-persona-v1/persona.sqlite").use { input ->
            database.outputStream().use { input.copyTo(it) }
        }
        val vectors = context.assets.open("retrieval/vectors.json").bufferedReader().use { it.readText() }
        val cases = parseJson(vectors)["cases"]!!
        assertEquals(7, cases.size())
        val opened = SqlCipherPackDatabase(database.path)
        cases.elements().forEach { case ->
            val result = searchPack(
                opened,
                case["query"]!!.asTextOrNull()!!,
                case["limit"]!!.asInt(),
                case["include_inactive"]!!.asBoolean(),
            )
            val id = case["id"]!!.asTextOrNull()
            assertEquals(id, case["strategy"]!!.asTextOrNull(), result.strategy)
            assertEquals(id, case["expected"]!!.elements().map { it.asTextOrNull() }, result.hits.map { it.documentKey })
        }
    }
}
