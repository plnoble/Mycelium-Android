package com.xavierxia.mycelium.roundtable

import com.xavierxia.mycelium.contract.ContractFiles
import com.xavierxia.mycelium.contract.PackDatabase
import com.xavierxia.mycelium.contract.SqliteJdbcPackDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText

class PackLibraryTest {
    private val files = ContractFiles(contractRoot())

    private fun contractRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("contract") }
        .first { it.resolve("checksums.json").toFile().isFile() }
    private val schema = files.text("persona-pack/manifest.schema.json")

    @Test
    fun validPackIsSavedAndARepeatedImportIsRecognized() {
        val root = Files.createTempDirectory("pack-library").toFile()
        val library = PackLibrary(root, schema)
        val bytes = files.samplePack().readBytes()

        val first = library.importBytes(bytes, ::open) as ImportOutcome.Imported
        assertEquals("陆川", first.version.displayName)
        assertEquals(null, first.added)
        assertTrue(first.version.file.isFile)
        assertEquals(File(root, "packs").absolutePath, first.version.file.parentFile?.absolutePath)
        assertEquals(1, library.listLatest().size)

        val second = library.importBytes(bytes, ::open)
        assertTrue(second is ImportOutcome.AlreadyImported)
        assertEquals(1, library.listAll().size)
    }

    @Test
    fun rejectedImportsLeaveNothingBehind() {
        val root = Files.createTempDirectory("pack-library-reject").toFile()
        val library = PackLibrary(root, schema)
        val pack = files.samplePack().readBytes()
        val manifest = files.path("samples/synthetic-persona-v1/manifest.json").readBytes()
        val sqlite = files.sampleDatabase().readBytes()
        val rejected = listOf(
            "改一个字节" to pack.also { it[it.size / 2] = it[it.size / 2].inc() },
            "缺条目" to zipOf("manifest.json" to manifest),
            "条目顺序错" to zipOf("persona.sqlite" to sqlite, "manifest.json" to manifest),
            "pack_format 不对" to zipOf(
                "manifest.json" to String(manifest).replace("mycelium-persona-pack", "other-pack").toByteArray(),
                "persona.sqlite" to sqlite,
            ),
            "contract_version 不兼容" to zipOf(
                "manifest.json" to String(manifest).replace("\"contract_version\": \"0.1.0\"", "\"contract_version\": \"0.2.0\"").toByteArray(),
                "persona.sqlite" to sqlite,
            ),
            "截断的角色包" to pack.copyOf(pack.size / 2),
            "不是数据库" to zipOf("manifest.json" to manifest, "persona.sqlite" to "not a database".toByteArray()),
            "目录条目" to zipOf("manifest.json" to manifest, "extra/" to ByteArray(0), "persona.sqlite" to sqlite),
        )
        rejected.forEach { (label, bytes) ->
            val outcome = library.importBytes(bytes, ::open)
            assertTrue("$label 应当被拒，实际是 $outcome", outcome is ImportOutcome.Rejected)
            assertTrue((outcome as ImportOutcome.Rejected).message.isNotEmpty())
        }
        assertTrue(library.listAll().isEmpty())
        val packs = File(root, "packs")
        assertTrue(!packs.exists() || packs.listFiles().isNullOrEmpty())
    }

    @Test
    fun aNewVersionReportsAddedAndRemovedClaims() {
        val root = Files.createTempDirectory("pack-library-version").toFile()
        val library = PackLibrary(root, schema)
        val original = library.importBytes(files.samplePack().readBytes(), ::open) as ImportOutcome.Imported

        val unchangedOutcome = library.importBytes(reversionedPack(), ::open)
        assertTrue(unchangedOutcome.toString(), unchangedOutcome is ImportOutcome.Imported)
        val unchanged = unchangedOutcome as ImportOutcome.Imported
        assertEquals("v2", unchanged.version.versionLabel)
        assertEquals(0, unchanged.added)
        assertEquals(0, unchanged.removed)

        val changedOutcome = library.importBytes(reversionedPack(version = 3, without = "LC-C11", extra = "LC-C12"), ::open)
        assertTrue(changedOutcome.toString(), changedOutcome is ImportOutcome.Imported)
        val changed = changedOutcome as ImportOutcome.Imported
        assertEquals(1, changed.added)
        assertEquals(1, changed.removed)
        assertEquals("v3", library.listLatest().single().versionLabel)
        assertEquals(3, library.listAll().size)
        assertEquals(original.version.versionId, library.find("luchuan-synthetic-v1", original.version.versionId)?.versionId)
    }

    @Test
    fun deletingAVersionRemovesTheFileAndTheRecord() {
        val root = Files.createTempDirectory("pack-library-delete").toFile()
        val library = PackLibrary(root, schema)
        val imported = library.importBytes(files.samplePack().readBytes(), ::open) as ImportOutcome.Imported
        val file = imported.version.file
        assertTrue(file.isFile)

        assertTrue(library.delete(imported.version.personaId, imported.version.versionId))
        assertFalse(file.exists())
        assertTrue(library.listAll().isEmpty())
        assertEquals(null, library.find(imported.version.personaId, imported.version.versionId))
    }

    /** A later version of the same database. Claim ids are renamed, so the diff is exact and the hash stays valid. */
    private fun reversionedPack(version: Int = 2, without: String? = null, extra: String? = null): ByteArray {
        val manifest = JSONObject(files.path("samples/synthetic-persona-v1/manifest.json").readText())
        manifest.put("version_id", "ver-luchuan-v$version")
        manifest.put("version_label", "v$version")
        manifest.put("published_at", "2025-0$version-01T00:00:00Z")
        val edited = editSqlite(files.sampleDatabase().readBytes()) { connection ->
            connection.createStatement().use { statement ->
                if (without != null && extra != null) {
                    statement.executeUpdate("UPDATE claims SET claim_id = '$extra' WHERE claim_id = '$without'")
                    statement.executeUpdate("UPDATE theme_claims SET claim_id = '$extra' WHERE claim_id = '$without'")
                    statement.executeUpdate("UPDATE claim_evidence SET claim_id = '$extra' WHERE claim_id = '$without'")
                    statement.executeUpdate("UPDATE relationships SET from_claim_id = '$extra' WHERE from_claim_id = '$without'")
                    statement.executeUpdate("UPDATE relationships SET to_claim_id = '$extra' WHERE to_claim_id = '$without'")
                    statement.executeUpdate("UPDATE search_docs SET claim_id = '$extra' WHERE claim_id = '$without'")
                }
            }
        }
        manifest.put("logical_sha256", com.xavierxia.mycelium.contract.logicalSha256(open(edited)))
        return zipOf("manifest.json" to manifest.toString().toByteArray(), "persona.sqlite" to edited)
    }

    private fun editSqlite(bytes: ByteArray, edit: (java.sql.Connection) -> Unit): ByteArray {
        val path = Files.createTempFile("edited-pack-", ".sqlite")
        Files.write(path, bytes)
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use(edit)
        return path.readBytes()
    }

    private fun open(bytes: ByteArray): PackDatabase {
        val path = Files.createTempFile("opened-pack-", ".sqlite")
        path.toFile().deleteOnExit()
        Files.write(path, bytes)
        return SqliteJdbcPackDatabase(path.toString())
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }
}
