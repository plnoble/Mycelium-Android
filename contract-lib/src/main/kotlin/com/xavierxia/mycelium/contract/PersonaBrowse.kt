package com.xavierxia.mycelium.contract

/**
 * Read-only views of a pack that already passed [readPersonaPack].
 * The SQL follows `pack-db.sql`; retrieval itself stays in [searchPack].
 */
data class ThemeSummary(val themeId: String, val name: String, val summary: String, val claimCount: Int)

data class ClaimSummary(
    val claimId: String,
    val text: String,
    val kind: String,
    val sourceTitle: String,
)

data class EvidenceItem(
    val text: String,
    val sourceTitle: String,
    val platform: String?,
    val publishedAt: String?,
)

data class ClaimRelation(
    val otherClaimId: String,
    val otherText: String,
    val kind: String,
    val direction: String,
    val rationale: String,
)

data class ClaimDetail(
    val claimId: String,
    val text: String,
    val kind: String,
    val themes: List<String>,
    val evidence: List<EvidenceItem>,
    val relations: List<ClaimRelation>,
)

fun listThemes(database: PackDatabase): List<ThemeSummary> = database.read { db ->
    db.query(
        """
        SELECT t.theme_id, t.name, t.summary, COUNT(tc.claim_id)
        FROM themes t
        LEFT JOIN theme_claims tc ON tc.theme_id = t.theme_id
        GROUP BY t.theme_id
        ORDER BY t.theme_order, t.theme_id
        """.trimIndent(),
    ).map { row ->
        ThemeSummary(row.text(0), row.text(1), row.text(2), row.long(3).toInt())
    }
}

fun listClaims(database: PackDatabase, themeId: String? = null): List<ClaimSummary> = database.read { db ->
    val sql = if (themeId == null) {
        """
        SELECT c.claim_id, c.claim_text, c.claim_kind, s.title
        FROM claims c JOIN sources s ON s.source_id = c.source_id
        ORDER BY c.claim_id
        """.trimIndent()
    } else {
        """
        SELECT c.claim_id, c.claim_text, c.claim_kind, s.title
        FROM theme_claims tc
        JOIN claims c ON c.claim_id = tc.claim_id
        JOIN sources s ON s.source_id = c.source_id
        WHERE tc.theme_id = ?
        ORDER BY c.claim_id
        """.trimIndent()
    }
    db.query(sql) { statement ->
        if (themeId != null) statement.setString(1, themeId)
    }.map { row -> ClaimSummary(row.text(0), row.text(1), row.text(2), row.text(3)) }
}

/** Maps a search document key back to the claim a person can open, when the hit is a claim. */
fun claimIdForDocument(database: PackDatabase, documentKey: String): String? = database.read { db ->
    db.query("SELECT claim_id FROM search_docs WHERE document_key = ?") { it.setString(1, documentKey) }
        .singleOrNull()?.textOrNull(0)
}

fun claimIds(database: PackDatabase): Set<String> = database.read { db ->
    db.query("SELECT claim_id FROM claims").map { it.text(0) }.toSet()
}

/** Added and removed claim ids between two already-imported versions of one persona. */
fun claimDiff(previous: Set<String>, next: Set<String>): Pair<Int, Int> =
    (next - previous).size to (previous - next).size

fun loadClaim(database: PackDatabase, claimId: String): ClaimDetail? = database.read { db ->
    val row = db.query(
        """
        SELECT c.claim_text, c.claim_kind
        FROM claims c WHERE c.claim_id = ?
        """.trimIndent(),
    ) { it.setString(1, claimId) }.singleOrNull() ?: return@read null
    val themes = db.query(
        """
        SELECT t.name FROM theme_claims tc
        JOIN themes t ON t.theme_id = tc.theme_id
        WHERE tc.claim_id = ?
        ORDER BY t.theme_order, t.theme_id
        """.trimIndent(),
    ) { it.setString(1, claimId) }.map { it.text(0) }
    val evidence = db.query(
        """
        SELECT e.evidence_text, s.title, s.platform, s.published_at
        FROM claim_evidence e
        JOIN claims c ON c.claim_id = e.claim_id
        JOIN sources s ON s.source_id = c.source_id
        WHERE e.claim_id = ?
        ORDER BY e.body_start, e.evidence_id
        """.trimIndent(),
    ) { it.setString(1, claimId) }.map { item ->
        EvidenceItem(item.text(0), item.text(1), item.textOrNull(2), item.textOrNull(3))
    }
    val relations = db.query(
        """
        SELECT r.to_claim_id, other.claim_text, r.relationship_kind, r.rationale, '指出'
        FROM relationships r JOIN claims other ON other.claim_id = r.to_claim_id
        WHERE r.from_claim_id = ? AND r.status = 'accepted'
        UNION ALL
        SELECT r.from_claim_id, other.claim_text, r.relationship_kind, r.rationale, '被指出'
        FROM relationships r JOIN claims other ON other.claim_id = r.from_claim_id
        WHERE r.to_claim_id = ? AND r.status = 'accepted'
        ORDER BY 1
        """.trimIndent(),
    ) { statement ->
        statement.setString(1, claimId)
        statement.setString(2, claimId)
    }.map { item ->
        ClaimRelation(item.text(0), item.text(1), item.text(2), item.text(4), item.text(3))
    }
    ClaimDetail(claimId, row.text(0), row.text(1), themes, evidence, relations)
}
