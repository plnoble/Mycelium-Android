package com.xavierxia.mycelium.roundtable

import android.content.Context
import com.xavierxia.mycelium.contract.ContractFiles
import com.xavierxia.mycelium.contract.PackDatabase
import com.xavierxia.mycelium.contract.PackRejected
import com.xavierxia.mycelium.contract.PersonaManifest
import com.xavierxia.mycelium.contract.claimDiff
import com.xavierxia.mycelium.contract.claimIds
import com.xavierxia.mycelium.contract.readPersonaPack
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/** One imported version of a persona. The file lives in app-private storage. */
data class InstalledVersion(
    val personaId: String,
    val displayName: String,
    val versionId: String,
    val versionLabel: String,
    val publishedAt: String,
    val dataCutoff: String,
    val claims: Int,
    val themes: Int,
    val sources: Int,
    val importedAt: String,
    val bytes: Long,
    val file: File,
)

sealed class ImportOutcome {
    data class Imported(val version: InstalledVersion, val added: Int?, val removed: Int?) : ImportOutcome()
    data class AlreadyImported(val version: InstalledVersion) : ImportOutcome()
    data class Rejected(val message: String) : ImportOutcome()
}

/**
 * Validates a `.mpack` completely before anything is saved. A rejected import leaves no file.
 * Versions of one persona are kept side by side; the list shows the newest published one.
 */
class PackLibrary(private val root: File, private val schemaJson: String) {
    private val indexFile = File(root, "index.json")

    fun listLatest(): List<InstalledVersion> =
        readIndex().groupBy { it.personaId }.map { (_, versions) -> versions.maxBy { it.publishedAt } }
            .sortedBy { it.displayName }

    fun listAll(): List<InstalledVersion> = readIndex().sortedBy { it.importedAt }

    fun find(personaId: String, versionId: String): InstalledVersion? =
        readIndex().firstOrNull { it.personaId == personaId && it.versionId == versionId }

    fun importBytes(bytes: ByteArray, openDatabase: (ByteArray) -> PackDatabase): ImportOutcome {
        val pack = try {
            readPersonaPack(bytes, schemaJson, openDatabase)
        } catch (error: PackRejected) {
            return ImportOutcome.Rejected(error.message ?: "角色包没有通过校验")
        }
        val existing = readIndex()
        val same = existing.firstOrNull {
            it.personaId == pack.manifest.personaId && it.versionId == pack.manifest.versionId
        }
        if (same != null) return ImportOutcome.AlreadyImported(same)

        val previous = existing.filter { it.personaId == pack.manifest.personaId }.maxByOrNull { it.publishedAt }
        val addedRemoved = previous?.let { older ->
            claimDiff(claimIds(openDatabase(older.file.readBytes())), claimIds(openDatabase(pack.sqliteBytes)))
        }
        val version = writeVersion(pack.manifest, pack.sqliteBytes)
        writeIndex(existing + version)
        return ImportOutcome.Imported(version, addedRemoved?.first, addedRemoved?.second)
    }

    fun delete(personaId: String, versionId: String): Boolean {
        val existing = readIndex()
        val target = existing.firstOrNull { it.personaId == personaId && it.versionId == versionId } ?: return false
        target.file.delete()
        writeIndex(existing.filterNot { it.personaId == personaId && it.versionId == versionId })
        return !target.file.exists() && find(personaId, versionId) == null
    }

    private fun writeVersion(manifest: PersonaManifest, sqlite: ByteArray): InstalledVersion {
        val digest = MessageDigest.getInstance("SHA-256").digest(sqlite).joinToString("") { "%02x".format(it) }
        val file = File(root, "packs/$digest.sqlite")
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "$digest.sqlite.partial")
        temporary.writeBytes(sqlite)
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
        return manifest.toInstalled(file, Instant.now().toString())
    }

    private fun readIndex(): List<InstalledVersion> {
        if (!indexFile.isFile) return emptyList()
        val array = JSONObject(indexFile.readText())
        val items = array.optJSONArray("versions") ?: return emptyList()
        return List(items.length()) { index -> items.getJSONObject(index).toInstalled(root) }
            .filter { it.file.isFile }
    }

    private fun writeIndex(versions: List<InstalledVersion>) {
        root.mkdirs()
        val array = org.json.JSONArray()
        versions.forEach { version -> array.put(version.toJson()) }
        val body = JSONObject().put("versions", array)
        val temporary = File(root, "index.json.partial")
        temporary.writeText(body.toString())
        if (!temporary.renameTo(indexFile)) {
            indexFile.writeText(body.toString())
            temporary.delete()
        }
    }
}

private fun PersonaManifest.toInstalled(file: File, importedAt: String) = InstalledVersion(
    personaId = personaId,
    displayName = displayName,
    versionId = versionId,
    versionLabel = versionLabel,
    publishedAt = publishedAt,
    dataCutoff = dataCutoff,
    claims = counts.claims,
    themes = counts.themes,
    sources = counts.sources,
    importedAt = importedAt,
    bytes = file.length(),
    file = file,
)

private fun InstalledVersion.toJson(): JSONObject = JSONObject()
    .put("persona_id", personaId)
    .put("display_name", displayName)
    .put("version_id", versionId)
    .put("version_label", versionLabel)
    .put("published_at", publishedAt)
    .put("data_cutoff", dataCutoff)
    .put("claims", claims)
    .put("themes", themes)
    .put("sources", sources)
    .put("imported_at", importedAt)
    .put("bytes", bytes)
    .put("file", file.name)

private fun JSONObject.toInstalled(root: File) = InstalledVersion(
    personaId = getString("persona_id"),
    displayName = getString("display_name"),
    versionId = getString("version_id"),
    versionLabel = getString("version_label"),
    publishedAt = getString("published_at"),
    dataCutoff = getString("data_cutoff"),
    claims = getInt("claims"),
    themes = getInt("themes"),
    sources = getInt("sources"),
    importedAt = getString("imported_at"),
    bytes = getLong("bytes"),
    file = File(root, "packs/${getString("file")}"),
)

fun schemaJson(context: Context): String = context.assets.open("contract/persona-pack/manifest.schema.json")
    .bufferedReader().use { it.readText() }

/** Test and instrumented callers that already have the contract tree on disk. */
fun schemaJson(files: ContractFiles): String = files.text("persona-pack/manifest.schema.json")
