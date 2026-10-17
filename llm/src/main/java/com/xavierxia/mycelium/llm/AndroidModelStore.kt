package com.xavierxia.mycelium.llm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.InputStream

class AndroidModelStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val documents = object : ModelDocuments {
        override fun describe(uri: String): DocumentDetails {
            require(Uri.parse(uri).scheme == "content") { "请用系统文件选择器选择模型" }
            resolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    require(cursor.moveToFirst()) { "无法读取文件信息" }
                    val name = cursor.getString(0) ?: "model.gguf"
                    val size = if (cursor.isNull(1)) null else cursor.getLong(1).takeIf { it >= 0 }
                    return DocumentDetails(name, size)
                }
            error("无法读取文件信息")
        }
        override fun retain(uri: String): DocumentGrant {
            val parsed = Uri.parse(uri)
            if (resolver.persistedUriPermissions.any { it.uri == parsed && it.isReadPermission })
                return DocumentGrant(true, false)
            return try {
                resolver.takePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                DocumentGrant(true, true)
            } catch (_: SecurityException) { DocumentGrant(false, false) }
        }
        override fun supportsDirectRead(uri: String): Boolean = runCatching {
            resolver.openFileDescriptor(Uri.parse(uri), "r")!!.use { fd ->
                Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
                // JNI uses dup + fdopen + llama_model_load_from_file_ptr; no path reopen is needed.
                true
            }
        }.getOrDefault(false)
        override fun open(uri: String): InputStream =
            checkNotNull(resolver.openInputStream(Uri.parse(uri))) { "文件无法读取，请重新选择" }
        override fun release(uri: String) =
            resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val registry = ModelRegistry(File(context.applicationContext.filesDir, "local-models"), documents)
    fun open(record: LocalModelRecord): ModelFile {
        val descriptor = descriptor(record)
        return object : ModelFile {
            override val fd: Int get() = descriptor.fd
            override fun close() = descriptor.close()
        }
    }
    private fun descriptor(record: LocalModelRecord): ParcelFileDescriptor =
        if (record.copied) ParcelFileDescriptor.open(registry.copyFile(record), ParcelFileDescriptor.MODE_READ_ONLY)
        else checkNotNull(resolver.openFileDescriptor(Uri.parse(record.uri), "r")) { "文件授权已失效，请重新导入" }
}



