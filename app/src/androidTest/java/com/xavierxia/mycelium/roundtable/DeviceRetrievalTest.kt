package com.xavierxia.mycelium.roundtable

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xavierxia.mycelium.contract.claimIdForDocument
import com.xavierxia.mycelium.contract.listClaims
import com.xavierxia.mycelium.contract.listThemes
import com.xavierxia.mycelium.contract.loadClaim
import com.xavierxia.mycelium.contract.parseJson
import com.xavierxia.mycelium.contract.searchPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** Themes, claims, claim detail, and search-hit lookup all bind parameters. */
    @Test
    fun boundQueriesReachTheSamplePack() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val database = File(target.cacheDir, "synthetic-persona-v1-bound.sqlite")
        context.assets.open("samples/synthetic-persona-v1/persona.sqlite").use { input ->
            database.outputStream().use { input.copyTo(it) }
        }
        val opened = SqlCipherPackDatabase(database.path)
        val themes = listThemes(opened)
        assertEquals(3, themes.size)
        assertEquals(11, listClaims(opened, null).size)
        assertTrue(listClaims(opened, themes.first().themeId).isNotEmpty())
        val detail = loadClaim(opened, "LC-C1")
        assertTrue(detail != null)
        assertTrue(detail!!.evidence.isNotEmpty())
        assertTrue(detail.relations.isNotEmpty())
        assertEquals("LC-C1", claimIdForDocument(opened, "claim:lc-claim-record-1"))
    }
}
