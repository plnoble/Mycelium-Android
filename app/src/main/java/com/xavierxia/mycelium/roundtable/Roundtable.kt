package com.xavierxia.mycelium.roundtable

import android.content.Context
import android.net.Uri
import com.xavierxia.mycelium.contract.ClaimDetail
import com.xavierxia.mycelium.contract.ClaimSummary
import com.xavierxia.mycelium.contract.PackDatabase
import com.xavierxia.mycelium.contract.SearchResult
import com.xavierxia.mycelium.contract.ThemeSummary
import com.xavierxia.mycelium.contract.claimIdForDocument
import com.xavierxia.mycelium.contract.claimIds
import com.xavierxia.mycelium.contract.listClaims
import com.xavierxia.mycelium.contract.listThemes
import com.xavierxia.mycelium.contract.loadClaim
import com.xavierxia.mycelium.contract.searchPack
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** Everything the roundtable screens read. The UI does not open files itself. */
class Roundtable(context: Context) {
    private val library = PackLibrary(File(context.filesDir, "roundtable"), schemaJson(context))

    fun personas(): List<InstalledVersion> = library.listLatest()

    fun installed(): List<InstalledVersion> = library.listAll()

    fun importUri(uri: Uri, context: Context, openDatabase: (ByteArray) -> PackDatabase): ImportOutcome {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_WHOLE_PACK_BYTES) }
                ?: return ImportOutcome.Rejected("没有读到选择的文件")
        } catch (rejected: PackTooLarge) {
            return ImportOutcome.Rejected(rejected.message ?: "角色包太大")
        } catch (failure: Exception) {
            return ImportOutcome.Rejected("没有读到选择的文件")
        }
        return library.importBytes(bytes, openDatabase)
    }

    fun delete(version: InstalledVersion): Boolean = library.delete(version.personaId, version.versionId)

    fun themes(version: InstalledVersion, open: (File) -> PackDatabase): List<ThemeSummary> =
        open(version.file).let(::listThemes)

    fun claims(version: InstalledVersion, themeId: String?, open: (File) -> PackDatabase): List<ClaimSummary> =
        listClaims(open(version.file), themeId)

    fun claim(version: InstalledVersion, claimId: String, open: (File) -> PackDatabase): ClaimDetail? =
        loadClaim(open(version.file), claimId)

    fun search(version: InstalledVersion, query: String, open: (File) -> PackDatabase): SearchResult =
        searchPack(open(version.file), query, limit = 20, includeInactive = false)

    fun claimForHit(version: InstalledVersion, documentKey: String, open: (File) -> PackDatabase): String? =
        claimIdForDocument(open(version.file), documentKey)

    fun claimCount(version: InstalledVersion, open: (File) -> PackDatabase): Int =
        claimIds(open(version.file)).size
}

/** Same ceiling as one zip entry. The whole file is read before unzipping, so it is capped here too. */
internal const val MAX_WHOLE_PACK_BYTES = 64L * 1024 * 1024

private class PackTooLarge : Exception("角色包超过 64 MB，没有导入")

private fun InputStream.readAtMost(limit: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw PackTooLarge()
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
