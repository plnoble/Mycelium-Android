package com.xavierxia.mycelium.contract

import java.util.zip.ZipInputStream

/** A persona pack that passed the container, schema and logical-hash checks. */
data class PersonaPack(
    val manifest: PersonaManifest,
    val sqliteBytes: ByteArray,
)

data class PersonaManifest(
    val packFormat: String,
    val packVersion: String,
    val contractVersion: String,
    val personaId: String,
    val displayName: String,
    val versionId: String,
    val versionLabel: String,
    val publishedAt: String,
    val dataCutoff: String,
    val counts: PackCounts,
    val ftsTokenizer: String,
    val answerProtocolVersion: String,
    val includesFullText: Boolean,
    val logicalSha256: String,
)

data class PackCounts(
    val sources: Int,
    val claims: Int,
    val themes: Int,
    val relationships: Int,
    val evidence: Int,
)

class PackRejected(message: String) : Exception(message)

/** v0 accepts only contract version 0.1.0. Later versions stay readable until support is dropped. */
fun contractVersionCompatible(contractVersion: String): Boolean = contractVersion == "0.1.0"

/**
 * Reads a `.mpack`. The two entries must be `manifest.json` then `persona.sqlite`, in that order,
 * and the recomputed logical hash must equal the manifest. Nothing is trusted before that.
 */
fun readPersonaPack(bytes: ByteArray, schemaJson: String, openDatabase: (ByteArray) -> PackDatabase): PersonaPack {
    val entries = readZipEntries(bytes)
    if (entries.map { it.first } != listOf("manifest.json", "persona.sqlite")) {
        throw PackRejected(
            "角色包必须且只能按顺序包含 manifest.json 和 persona.sqlite，实际是 ${entries.map { it.first }}",
        )
    }
    val manifest = validateManifest(entries[0].second, schemaJson)
    if (!contractVersionCompatible(manifest.contractVersion)) {
        throw PackRejected("不兼容的契约版本：${manifest.contractVersion}")
    }
    val actual = logicalSha256(openDatabase(entries[1].second))
    if (actual != manifest.logicalSha256) {
        throw PackRejected("logical_sha256 不一致：manifest 是 ${manifest.logicalSha256}，重算是 $actual")
    }
    return PersonaPack(manifest, entries[1].second)
}

/** Validates manifest bytes against the contract schema and returns the fields consumers use. */
fun validateManifest(manifestBytes: ByteArray, schemaJson: String): PersonaManifest {
    val node = try {
        parseJson(manifestBytes.toString(Charsets.UTF_8))
    } catch (error: Exception) {
        throw PackRejected("manifest.json 不是合法 JSON：${error.message}")
    }
    val schema = parseJson(schemaJson)
    val messages = validateManifestNode(node, schema)
    if (messages.isNotEmpty()) throw PackRejected("manifest 未通过 schema 校验：${messages.joinToString("；")}")
    val counts = node["counts"] ?: throw PackRejected("manifest 缺少 counts")
    return PersonaManifest(
        packFormat = node.require("pack_format"),
        packVersion = node.require("pack_version"),
        contractVersion = node.require("contract_version"),
        personaId = node.require("persona_id"),
        displayName = node.require("display_name"),
        versionId = node.require("version_id"),
        versionLabel = node.require("version_label"),
        publishedAt = node.require("published_at"),
        dataCutoff = node.require("data_cutoff"),
        counts = PackCounts(
            sources = counts.requireInt("sources"),
            claims = counts.requireInt("claims"),
            themes = counts.requireInt("themes"),
            relationships = counts.requireInt("relationships"),
            evidence = counts.requireInt("evidence"),
        ),
        ftsTokenizer = node.require("fts_tokenizer"),
        answerProtocolVersion = node.require("answer_protocol_version"),
        includesFullText = node["includes_full_text"]?.asBooleanOrNull()
            ?: throw PackRejected("manifest 的 includes_full_text 不是布尔"),
        logicalSha256 = node.require("logical_sha256"),
    )
}

private fun JsonValue.require(field: String): String =
    this[field]?.asTextOrNull() ?: throw PackRejected("manifest 的 $field 不是字符串")

private fun JsonValue.requireInt(field: String): Int =
    this[field]?.asIntOrNull() ?: throw PackRejected("manifest 的 $field 不是整数")

private fun readZipEntries(bytes: ByteArray): List<Pair<String, ByteArray>> {
    val entries = mutableListOf<Pair<String, ByteArray>>()
    ZipInputStream(bytes.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.forEach { entry ->
            if (!entry.isDirectory) entries += entry.name to zip.readBytes()
        }
    }
    return entries
}
