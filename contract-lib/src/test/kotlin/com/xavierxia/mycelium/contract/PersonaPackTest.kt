package com.xavierxia.mycelium.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readBytes
import kotlin.io.path.readText

class PersonaPackTest {
    private val files = ContractFiles(contractRoot())
    private val schema = files.text("persona-pack/manifest.schema.json")

    @Test
    fun sampleManifestPassesSchemaAndLogicalHash() {
        val manifest = validateManifest(
            files.path("samples/synthetic-persona-v1/manifest.json").readBytes(),
            schema,
        )
        assertEquals("陆川", manifest.displayName)
        assertEquals("0.1.0", manifest.contractVersion)
        val recomputed = logicalSha256(SqliteJdbcPackDatabase(files.sampleDatabase().toString()))
        assertEquals(manifest.logicalSha256, recomputed)
        assertEquals("9e5e3e8331073c44eaed8a7925c4edaa67fdee8271c68312e9ffe3d330883c69", recomputed)
    }

    @Test
    fun sampleMpackReadsInEntryOrder() {
        val pack = readPersonaPack(files.samplePack().readBytes(), schema) { bytes ->
            val temp = Files.createTempFile("persona-pack-", ".sqlite")
            temp.toFile().deleteOnExit()
            Files.write(temp, bytes)
            SqliteJdbcPackDatabase(temp.toString())
        }
        assertEquals("luchuan-synthetic-v1", pack.manifest.personaId)
        assertEquals(11, pack.manifest.counts.claims)
    }
}
