package com.xavierxia.mycelium.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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

    @Test
    fun manifestRejectsSchemaViolationsAsPackRejected() {
        val original = files.path("samples/synthetic-persona-v1/manifest.json").readText()
        assertPackRejected("缺必填字段") {
            validateManifest(original.replace("\"display_name\": \"陆川\",\n", "").toByteArray(), schema)
        }
        assertPackRejected("多出未定义字段") {
            validateManifest(original.replaceFirst("{", "{\"extra\": true,").toByteArray(), schema)
        }
        assertPackRejected("const 写成数字") {
            validateManifest(original.replace("\"pack_version\": \"0.1\"", "\"pack_version\": 0.1").toByteArray(), schema)
        }
        assertPackRejected("时间格式不对") {
            validateManifest(original.replace("2024-02-10T08:00:00Z", "not-a-date").toByteArray(), schema)
        }
        assertPackRejected("计数是字符串") {
            validateManifest(original.replace("\"sources\": 4", "\"sources\": \"4\"").toByteArray(), schema)
        }
        assertPackRejected("计数为负") {
            validateManifest(original.replace("\"sources\": 4", "\"sources\": -1").toByteArray(), schema)
        }
        assertPackRejected("契约版本不兼容") {
            validateManifest(original.replace("\"contract_version\": \"0.1.0\"", "\"contract_version\": \"0.2.0\"").toByteArray(), schema)
        }
    }

    @Test
    fun mpackRejectsBadEntriesAndATamperedHash() {
        val manifest = files.path("samples/synthetic-persona-v1/manifest.json").readBytes()
        val sqlite = files.sampleDatabase().readBytes()
        val open = openDatabase()
        assertPackRejected("条目顺序反了") { readPersonaPack(zipOf("persona.sqlite" to sqlite, "manifest.json" to manifest), schema, open) }
        assertPackRejected("多出条目") {
            readPersonaPack(zipOf("manifest.json" to manifest, "persona.sqlite" to sqlite, "extra.txt" to byteArrayOf(1)), schema, open)
        }
        val tampered = String(manifest, Charsets.UTF_8).replace(
            "9e5e3e8331073c44eaed8a7925c4edaa67fdee8271c68312e9ffe3d330883c69",
            "0000000000000000000000000000000000000000000000000000000000000000",
        )
        assertPackRejected("logical_sha256 被改") {
            readPersonaPack(zipOf("manifest.json" to tampered.toByteArray(), "persona.sqlite" to sqlite), schema, open)
        }
    }

    @Test
    fun truncatedPackAndBadDatabaseArePackRejected() {
        val pack = files.samplePack().readBytes()
        val open = openDatabase()
        assertPackRejected("截断的角色包") { readPersonaPack(pack.copyOf(pack.size / 2), schema, open) }
        assertPackRejected("末尾少 1 字节") { readPersonaPack(pack.copyOf(pack.size - 1), schema, open) }
        assertPackRejected("末尾少 22 字节") { readPersonaPack(pack.copyOf(pack.size - 22), schema, open) }
        assertPackRejected("空文件") { readPersonaPack(ByteArray(0), schema, open) }
        val manifest = files.path("samples/synthetic-persona-v1/manifest.json").readBytes()
        assertPackRejected("persona.sqlite 不是数据库") {
            readPersonaPack(zipOf("manifest.json" to manifest, "persona.sqlite" to "这不是数据库".toByteArray()), schema, open)
        }
    }

    @Test
    fun directoryEntriesCountAndOversizedEntriesAreRejected() {
        val manifest = files.path("samples/synthetic-persona-v1/manifest.json").readBytes()
        val sqlite = files.sampleDatabase().readBytes()
        val open = openDatabase()
        assertPackRejected("目录条目也算多出的条目") {
            readPersonaPack(zipOf("manifest.json" to manifest, "notes/" to ByteArray(0), "persona.sqlite" to sqlite), schema, open)
        }
        assertPackRejected("单个条目超过上限") {
            readPersonaPack(
                zipOf("manifest.json" to manifest, "persona.sqlite" to ByteArray(MAX_PACK_ENTRY_BYTES.toInt() + 1)),
                schema,
                open,
            )
        }
    }

    private fun openDatabase(): (ByteArray) -> PackDatabase = { bytes ->
        val temp = Files.createTempFile("persona-pack-", ".sqlite")
        temp.toFile().deleteOnExit()
        Files.write(temp, bytes)
        SqliteJdbcPackDatabase(temp.toString())
    }

    private fun assertPackRejected(label: String, body: () -> Unit) {
        val error = assertThrows(PackRejected::class.java, body, label)
        assertTrue(error.message.orEmpty().isNotEmpty(), label)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }
}
