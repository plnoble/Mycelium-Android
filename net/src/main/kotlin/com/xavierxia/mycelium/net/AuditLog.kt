package com.xavierxia.mycelium.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AuditEntry(
    val at: String,
    val host: String,
    val purpose: GatewayPurpose,
    val sentCharacters: Int,
    val sentBody: String,
    val status: Int?,
    val succeeded: Boolean,
    /** Token usage the service reported. Recorded only, never used to block a call. */
    val promptTokens: Int?,
    val completionTokens: Int?,
)

/** Append-only record of everything the gateway sent. Credentials are not part of an entry. */
class AuditLog(private val file: File) {
    fun record(entry: AuditEntry) {
        val entries = read() + entry
        file.parentFile?.mkdirs()
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        val temporary = File(file.parentFile, "${file.name}.partial")
        temporary.writeText(JSONObject().put("entries", array).toString())
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }

    fun read(): List<AuditEntry> {
        if (!file.isFile) return emptyList()
        val array = JSONObject(file.readText()).optJSONArray("entries") ?: return emptyList()
        return List(array.length()) { index -> array.getJSONObject(index).toEntry() }
    }

    fun clear() {
        file.delete()
    }
}

private fun AuditEntry.toJson(): JSONObject = JSONObject()
    .put("at", at)
    .put("host", host)
    .put("purpose", purpose.name)
    .put("sentCharacters", sentCharacters)
    .put("sentBody", sentBody)
    .put("status", status ?: JSONObject.NULL)
    .put("succeeded", succeeded)
    .put("promptTokens", promptTokens ?: JSONObject.NULL)
    .put("completionTokens", completionTokens ?: JSONObject.NULL)

private fun JSONObject.toEntry(): AuditEntry = AuditEntry(
    at = getString("at"),
    host = getString("host"),
    purpose = GatewayPurpose.valueOf(getString("purpose")),
    sentCharacters = getInt("sentCharacters"),
    sentBody = getString("sentBody"),
    status = if (isNull("status")) null else getInt("status"),
    succeeded = getBoolean("succeeded"),
    promptTokens = intOrNull("promptTokens"),
    completionTokens = intOrNull("completionTokens"),
)

private fun JSONObject.intOrNull(name: String): Int? = if (has(name) && !isNull(name)) getInt(name) else null
