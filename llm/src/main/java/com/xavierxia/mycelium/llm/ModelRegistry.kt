package com.xavierxia.mycelium.llm

import com.xavierxia.mycelium.model.ModelInfo
import com.xavierxia.mycelium.model.ModelLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class DocumentDetails(val name: String, val size: Long?)
data class DocumentGrant(val usable: Boolean, val acquired: Boolean)
interface ModelDocuments {
    fun describe(uri: String): DocumentDetails
    fun retain(uri: String): DocumentGrant
    fun supportsDirectRead(uri: String): Boolean
    fun open(uri: String): InputStream
    fun release(uri: String)
}
data class LocalModelRecord(val uri: String, val name: String, val size: Long, val sha256: String, val copied: Boolean) {
    val info: ModelInfo get() = ModelInfo(sha256, name, ModelLocation.LOCAL)
}
data class ImportProgress(val bytes: Long, val total: Long?)
data class ImportResult(val model: LocalModelRecord, val duplicate: Boolean)
class CopyRequired(val document: DocumentDetails) : Exception("此文件需要复制到应用私有目录")

/** No source-delete operation exists in this boundary. All mutations are app-private. */
class ModelRegistry(private val directory: File, private val documents: ModelDocuments) {
    private val gate = Mutex()
    private val index get() = File(directory, "registry.json")
    fun copyFile(record: LocalModelRecord): File {
        require(record.copied && record.sha256.matches(Regex("[a-f0-9]{64}")))
        return File(directory, record.sha256 + ".gguf")
    }
    suspend fun list(): List<LocalModelRecord> = gate.withLock { withContext(Dispatchers.IO) { read() } }

    suspend fun import(
        uri: String, allowCopy: Boolean = false, progress: (ImportProgress) -> Unit = {},
    ): ImportResult = gate.withLock {
        withContext(Dispatchers.IO) {
            val records = read()
            records.firstOrNull { it.uri == uri }?.let { return@withContext ImportResult(it, true) }
            val detail = documents.describe(uri)
            require(detail.name.endsWith(".gguf", ignoreCase = true)) { "请选择 .gguf 模型文件" }
            val grant = documents.retain(uri)
            var keepGrant = false
            var temporary: File? = null
            var installedCopy: File? = null
            try {
                val direct = grant.usable && documents.supportsDirectRead(uri)
                if (!direct) requireNotNull(detail.size) { "文件提供器未报告大小，无法确认复制所需空间；请选择本机存储中的文件" }
                if (!direct && !allowCopy) throw CopyRequired(detail)
                directory.mkdirs()
                if (!direct) {
                    // Copy size has been established before asking for consent.
                    detail.size?.let { require(directory.usableSpace > it + 16L * 1024 * 1024) { "手机剩余空间不足" } }
                    temporary = File.createTempFile("import-", ".part", directory)
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                val header = ByteArray(4)
                var headerBytes = 0
                progress(ImportProgress(0, detail.size))
                val output = temporary?.outputStream()
                try {
                    documents.open(uri).use { input ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            for (i in 0 until minOf(count, 4 - headerBytes)) header[headerBytes++] = buffer[i]
                            digest.update(buffer, 0, count)
                            output?.write(buffer, 0, count)
                            bytes += count
                            progress(ImportProgress(bytes, detail.size))
                        }
                    }
                    output?.fd?.sync()
                } finally { output?.close() }
                require(headerBytes == 4 && header.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))) { "不是有效的 GGUF 文件" }
                require(detail.size == null || bytes == detail.size) { "校验期间文件大小发生变化，请重试" }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                records.firstOrNull { it.sha256 == hash }?.let { return@withContext ImportResult(it, true) }
                val record = LocalModelRecord(uri, detail.name, bytes, hash, !direct)
                if (!direct) {
                    installedCopy = copyFile(record)
                    Files.move(temporary!!.toPath(), installedCopy.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    temporary = null
                }
                currentCoroutineContext().ensureActive()
                write(records + record)
                keepGrant = direct
                installedCopy = null // now owned by the persisted record
                ImportResult(record, false)
            } finally {
                temporary?.delete()
                installedCopy?.delete()
                if (grant.acquired && !keepGrant) runCatching { documents.release(uri) }
            }
        }
    }
    suspend fun remove(record: LocalModelRecord) = gate.withLock {
        withContext(Dispatchers.IO) {
            val records = read()
            val stored = records.firstOrNull { it.sha256 == record.sha256 } ?: return@withContext
            write(records.filterNot { it.sha256 == stored.sha256 })
            if (stored.copied) copyFile(stored).delete()
            else runCatching { documents.release(stored.uri) }
        }
    }
    private fun read(): List<LocalModelRecord> {
        if (!index.exists()) return emptyList()
        val items = JSONArray(index.readText())
        return (0 until items.length()).map {
            val row = items.getJSONObject(it)
            LocalModelRecord(row.getString("uri"), row.getString("name"), row.getLong("size"),
                row.getString("sha256"), row.getBoolean("copied"))
        }
    }
    private fun write(records: List<LocalModelRecord>) {
        directory.mkdirs()
        val array = JSONArray()
        records.forEach {
            array.put(JSONObject().put("uri", it.uri).put("name", it.name).put("size", it.size)
                .put("sha256", it.sha256).put("copied", it.copied))
        }
        val temporary = File.createTempFile("registry-", ".tmp", directory)
        try {
            temporary.outputStream().use { out -> out.write(array.toString().toByteArray()); out.fd.sync() }
            Files.move(temporary.toPath(), index.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}


