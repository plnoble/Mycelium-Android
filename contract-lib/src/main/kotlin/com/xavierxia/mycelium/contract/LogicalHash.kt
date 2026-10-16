package com.xavierxia.mycelium.contract

import java.security.MessageDigest

/**
 * `logical_sha256` from persona-pack SPEC §3: tables in a fixed order, each row one JSON array,
 * integers exported as decimal strings, `*_json` columns left exactly as stored.
 */
fun logicalSha256(database: PackDatabase): String {
    val lines = database.read { query ->
        buildString {
            appendRows(query, "SELECT key, value FROM pack_info ORDER BY key") { row ->
                listOf(row.text(0), row.text(1))
            }
            appendRows(
                query,
                "SELECT persona_id, display_name, created_at, updated_at FROM persona ORDER BY persona_id",
            ) { row ->
                listOf(row.text(0), row.text(1), row.text(2), row.text(3))
            }
            appendRows(
                query,
                """
                SELECT source_id, source_kind, title, author, platform, published_at, source_url,
                       revision_id, status, content_sha256, file_sha256, normalized_body_byte_length
                FROM sources ORDER BY source_id
                """.trimIndent(),
            ) { row ->
                listOf(
                    row.text(0), row.text(1), row.text(2), row.textOrNull(3), row.textOrNull(4),
                    row.textOrNull(5), row.textOrNull(6), row.text(7), row.text(8), row.text(9),
                    row.text(10), row.long(11).toString(),
                )
            }
            appendRows(
                query,
                "SELECT source_id, normalized_body FROM source_texts ORDER BY source_id",
            ) { row ->
                listOf(row.text(0), row.text(1))
            }
            appendRows(
                query,
                """
                SELECT claim_id, claim_record_id, source_id, revision_id, claim_text, claim_kind,
                       claim_role, attribution_json, method_structure_json, confidence
                FROM claims ORDER BY claim_id
                """.trimIndent(),
            ) { row ->
                listOf(
                    row.text(0), row.text(1), row.text(2), row.text(3), row.text(4), row.text(5),
                    row.text(6), row.text(7), row.textOrNull(8), row.textOrNull(9),
                )
            }
            appendRows(
                query,
                """
                SELECT evidence_id, claim_id, body_start, body_end, evidence_text, evidence_sha256
                FROM claim_evidence ORDER BY claim_id, body_start, evidence_id
                """.trimIndent(),
            ) { row ->
                listOf(
                    row.text(0), row.text(1), row.long(2).toString(), row.long(3).toString(),
                    row.text(4), row.text(5),
                )
            }
            appendRows(
                query,
                """
                SELECT theme_id, theme_record_id, name, summary, payload_json, theme_order
                FROM themes ORDER BY theme_order, theme_id
                """.trimIndent(),
            ) { row ->
                listOf(
                    row.text(0), row.text(1), row.text(2), row.text(3), row.text(4),
                    row.long(5).toString(),
                )
            }
            appendRows(
                query,
                "SELECT theme_id, claim_id, membership_role FROM theme_claims ORDER BY theme_id, claim_id",
            ) { row ->
                listOf(row.text(0), row.text(1), row.text(2))
            }
            appendRows(
                query,
                """
                SELECT relationship_id, from_claim_id, to_claim_id, relationship_kind, impact_level,
                       rationale, status, payload_json
                FROM relationships ORDER BY relationship_id
                """.trimIndent(),
            ) { row ->
                List(8) { row.text(it) }
            }
            appendRows(
                query,
                """
                SELECT snapshot_id, version_id, version_label, published_at, data_cutoff_at,
                       snapshot_sha256
                FROM snapshots ORDER BY snapshot_id
                """.trimIndent(),
            ) { row ->
                List(6) { row.text(it) }
            }
            appendRows(
                query,
                """
                SELECT document_key, document_kind, source_id, claim_id, theme_id, source_status,
                       title, body
                FROM search_docs ORDER BY document_key
                """.trimIndent(),
            ) { row ->
                listOf(
                    row.text(0), row.text(1), row.textOrNull(2), row.textOrNull(3),
                    row.textOrNull(4), row.text(5), row.text(6), row.text(7),
                )
            }
        }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(lines.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun StringBuilder.appendRows(
    query: PackQuery,
    sql: String,
    cells: (PackRow) -> List<String?>,
) {
    query.query(sql).forEach { row ->
        append(jsonRow(cells(row)))
        append('\n')
    }
}
