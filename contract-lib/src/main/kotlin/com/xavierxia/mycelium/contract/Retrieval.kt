package com.xavierxia.mycelium.contract

/**
 * retrieval/SPEC.md, matching the Windows reference in `core/persona_pack.rs`.
 * String comparisons are case-insensitive; character counts are Unicode scalars.
 */
data class SearchHit(
    val rank: Int,
    val relevance: Double,
    val documentKey: String,
    val documentKind: String,
    val title: String,
    val excerpt: String,
    /** The source's published date, when the hit has one. A theme hit does not. */
    val sourcePublishedAt: String? = null,
)

data class SearchResult(
    val query: String,
    val strategy: String,
    val includeInactive: Boolean,
    val hint: String,
    val hits: List<SearchHit>,
)

class SearchRejected(message: String) : Exception(message)

private const val SHORT_HINT = "只搜索标题、观点、主题和方法字段，不扫描整篇正文"
private const val LONG_HINT = "优先 FTS5 trigram；仅在标题、观点、主题和方法字段追加二字词回退"

private val stopBigrams = setOf(
    "什么", "怎么", "如何", "为何", "为什", "是否", "可以", "应该", "作者", "认为", "请问", "一下", "这个", "那个",
)

fun searchPack(database: PackDatabase, query: String, limit: Int, includeInactive: Boolean): SearchResult {
    val trimmed = query.trim()
    if (trimmed.isEmpty() || trimmed.codePointCount() > 200) {
        throw SearchRejected("查询必须为 1 到 200 个字")
    }
    if (limit !in 1..50) throw SearchRejected("返回数量必须在 1 到 50 之间")

    val short = trimmed.codePointCount() < 3
    val hits = if (short) {
        restrictedHits(database, trimmed, limit, includeInactive)
    } else {
        hybridHits(database, trimmed, limit, includeInactive)
    }
    return SearchResult(
        query = trimmed,
        strategy = if (short) "restricted_fields" else "trigram_hybrid",
        includeInactive = includeInactive,
        hint = if (short) SHORT_HINT else LONG_HINT,
        hits = hits.mapIndexed { index, hit -> hit.copy(rank = index + 1) },
    )
}

/**
 * SPEC §5: lowercase, split where Rust `char::is_alphanumeric` is false, CJK bigrams minus
 * stop words, then sort. Counts and windows are Unicode scalars, not UTF-16 units.
 */
fun searchTerms(query: String): List<String> {
    val terms = sortedSetOf<String>()
    splitScalars(query.lowercase()) { !isAlphanumeric(it) }.forEach { segment ->
        if (segment.any { isCjk(it) }) {
            segment.windowed(2).forEach { window ->
                val term = window.joinToString("")
                if (term !in stopBigrams) terms += term
            }
        } else if (segment.size >= 2) {
            terms += segment.joinToString("")
        }
    }
    return terms.toList()
}

private fun restrictedHits(
    database: PackDatabase,
    query: String,
    limit: Int,
    includeInactive: Boolean,
): List<SearchHit> = database.read { db ->
    db.query(
        """
        SELECT d.document_key, d.document_kind, d.title,
               CASE WHEN instr(lower(d.title), lower(?)) > 0 THEN d.title ELSE substr(d.body, 1, 240) END,
               CASE WHEN instr(lower(d.title), lower(?)) > 0 THEN 100.0 ELSE 25.0 END,
               s.published_at
        FROM search_docs d
        LEFT JOIN sources s ON s.source_id = d.source_id
        WHERE (? = 1 OR d.source_status = 'active')
          AND (instr(lower(d.title), lower(?)) > 0
               OR (d.document_kind IN ('claim', 'theme', 'method') AND instr(lower(d.body), lower(?)) > 0))
        ORDER BY 5 DESC, d.document_key
        LIMIT ?
        """.trimIndent(),
    ) { statement ->
        statement.setString(1, query)
        statement.setString(2, query)
        statement.setLong(3, if (includeInactive) 1 else 0)
        statement.setString(4, query)
        statement.setString(5, query)
        statement.setLong(6, limit.toLong())
    }.map { it.toHit() }
}

private fun hybridHits(
    database: PackDatabase,
    query: String,
    limit: Int,
    includeInactive: Boolean,
): List<SearchHit> {
    val expression = splitScalars(query) { isUnicodeWhitespace(it) }
        .map { it.joinToString("") }
        .filter { it.isNotEmpty() }
        .joinToString(" AND ") { "\"${it.replace("\"", "\"\"")}\"" }
    val exact = database.read { db ->
        db.query(
            """
            SELECT d.document_key, d.document_kind, d.title,
                   snippet(search_fts, 1, '[', ']', '…', 24),
                   bm25(search_fts, 4.0, 1.0),
                   s.published_at
            FROM search_fts JOIN search_docs d ON d.document_id = search_fts.rowid
            LEFT JOIN sources s ON s.source_id = d.source_id
            WHERE search_fts MATCH ? AND (? = 1 OR d.source_status = 'active')
            ORDER BY bm25(search_fts, 4.0, 1.0), d.document_key
            LIMIT ?
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, expression)
            statement.setLong(2, if (includeInactive) 1 else 0)
            statement.setLong(3, limit.toLong())
        }.map { row -> row.toHit().copy(relevance = -row.double(4) + 1000.0) }
    }
    val seen = exact.map { it.documentKey }.toMutableSet()
    val merged = exact.toMutableList()
    fallbackHits(database, query, limit, includeInactive).forEach { hit ->
        if (seen.add(hit.documentKey)) merged += hit
    }
    return merged.sortedWith(compareByDescending<SearchHit> { it.relevance }.thenBy { it.documentKey })
        .take(limit)
}

private fun fallbackHits(
    database: PackDatabase,
    query: String,
    limit: Int,
    includeInactive: Boolean,
): List<SearchHit> {
    val terms = searchTerms(query)
    if (terms.isEmpty()) return emptyList()
    val score = terms.indices.joinToString(" + ") { index ->
        val title = index * 2 + 2
        val body = title + 1
        "CASE WHEN instr(lower(d.title), lower(?$title)) > 0 THEN 4 ELSE 0 END + " +
            "CASE WHEN instr(lower(d.body), lower(?$body)) > 0 THEN 1 ELSE 0 END"
    }
    val conditions = terms.indices.joinToString(" OR ") { index ->
        val title = index * 2 + 2
        val body = title + 1
        "instr(lower(d.title), lower(?$title)) > 0 OR instr(lower(d.body), lower(?$body)) > 0"
    }
    val minimum = if (terms.size >= 4) 2 else 1
    val limitParameter = terms.size * 2 + 2
    return database.read { db ->
        db.query(
            """
            SELECT d.document_key, d.document_kind, d.title, substr(d.body, 1, 240), ($score),
                   s.published_at
            FROM search_docs d
            LEFT JOIN sources s ON s.source_id = d.source_id
            WHERE d.document_kind IN ('claim', 'theme', 'method')
              AND (?1 = 1 OR d.source_status = 'active')
              AND ($conditions) AND ($score) >= $minimum
            ORDER BY 5 DESC, d.document_key
            LIMIT ?$limitParameter
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, if (includeInactive) 1 else 0)
            terms.forEachIndexed { index, term ->
                statement.setString(index * 2 + 2, term)
                statement.setString(index * 2 + 3, term)
            }
            statement.setLong(limitParameter, (limit.toLong() * 5).coerceAtMost(500))
        }.map { it.toHit() }
    }
}

private fun PackRow.toHit() = SearchHit(
    rank = 0,
    relevance = double(4),
    documentKey = text(0),
    documentKind = text(1),
    title = text(2),
    excerpt = text(3),
    sourcePublishedAt = textOrNull(5),
)

private fun isCjk(scalar: String): Boolean {
    val code = scalar.codePointAt(0)
    return code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF || code in 0xF900..0xFAFF
}

/** Rust `char::is_alphanumeric`: Alphabetic, or number class Nd / Nl / No. */
private fun isAlphanumeric(codePoint: Int): Boolean {
    val type = Character.getType(codePoint)
    return Character.isAlphabetic(codePoint) ||
        type == Character.DECIMAL_DIGIT_NUMBER.toInt() ||
        type == Character.LETTER_NUMBER.toInt() ||
        type == Character.OTHER_NUMBER.toInt()
}

/**
 * Rust `split_whitespace` / `char::is_whitespace`: Unicode White_Space.
 * That includes the non-breaking spaces U+00A0, U+2007 and U+202F, and excludes U+001C–U+001F.
 * Java `Character.isWhitespace` is the opposite on those seven scalars, so it is not used here.
 */
private fun isUnicodeWhitespace(codePoint: Int): Boolean =
    codePoint in 0x09..0x0D || codePoint == 0x85 || Character.isSpaceChar(codePoint)

/** Splits on Unicode scalars. A predicate true means a separator, matching Rust `split`. */
private fun splitScalars(text: String, isSeparator: (Int) -> Boolean): List<List<String>> {
    val segments = mutableListOf<List<String>>()
    val current = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val count = Character.charCount(codePoint)
        if (isSeparator(codePoint)) {
            segments += current.toList()
            current.clear()
        } else {
            current += text.substring(index, index + count)
        }
        index += count
    }
    segments += current.toList()
    return segments
}

private fun String.codePointCount(): Int = codePointCount(0, length)
