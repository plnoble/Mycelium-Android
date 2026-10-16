-- 知衍角色包（mycelium-persona-pack）包内只读 SQLite 建表语句。
-- 契约版本：0.1.0。要求 SQLite >= 3.34 且启用 FTS5（trigram tokenizer）。
-- 语义与桌面端 persona.db 对齐；本文件是 pack v0 的权威定义。

PRAGMA page_size = 4096;
PRAGMA journal_mode = OFF;
PRAGMA application_id = 1702061522; -- 'mpck' 的固定标识，便于识别角色包数据库

CREATE TABLE pack_info (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE persona (
    persona_id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- 只收录已确认发布版本的当前修订；includes_full_text=0 时 source_texts 为空表。
CREATE TABLE sources (
    source_id TEXT PRIMARY KEY,
    source_kind TEXT NOT NULL DEFAULT 'article',
    title TEXT NOT NULL,
    author TEXT,
    platform TEXT,
    published_at TEXT,
    source_url TEXT,
    revision_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('active', 'inactive')),
    content_sha256 TEXT NOT NULL,
    file_sha256 TEXT NOT NULL,
    normalized_body_byte_length INTEGER NOT NULL
);

CREATE TABLE source_texts (
    source_id TEXT PRIMARY KEY REFERENCES sources(source_id),
    normalized_body TEXT NOT NULL
);

CREATE TABLE claims (
    claim_id TEXT PRIMARY KEY,
    -- 桌面端 persona.db 的行主键：检索 document_key 的排序身份（R4）。
    claim_record_id TEXT NOT NULL UNIQUE,
    source_id TEXT NOT NULL REFERENCES sources(source_id),
    revision_id TEXT NOT NULL,
    claim_text TEXT NOT NULL,
    claim_kind TEXT NOT NULL,
    claim_role TEXT NOT NULL,
    attribution_json TEXT NOT NULL,
    method_structure_json TEXT,
    confidence TEXT
);

CREATE TABLE claim_evidence (
    evidence_id TEXT PRIMARY KEY,
    claim_id TEXT NOT NULL REFERENCES claims(claim_id) ON DELETE CASCADE,
    body_start INTEGER NOT NULL,
    body_end INTEGER NOT NULL,
    evidence_text TEXT NOT NULL,
    evidence_sha256 TEXT NOT NULL,
    CHECK (body_start >= 0 AND body_end >= body_start)
);

CREATE TABLE themes (
    theme_id TEXT PRIMARY KEY,
    theme_record_id TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    summary TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    theme_order INTEGER NOT NULL
);

CREATE TABLE theme_claims (
    theme_id TEXT NOT NULL REFERENCES themes(theme_id) ON DELETE CASCADE,
    claim_id TEXT NOT NULL REFERENCES claims(claim_id) ON DELETE CASCADE,
    membership_role TEXT NOT NULL,
    PRIMARY KEY (theme_id, claim_id)
);

CREATE TABLE relationships (
    relationship_id TEXT PRIMARY KEY,
    from_claim_id TEXT NOT NULL,
    to_claim_id TEXT NOT NULL,
    relationship_kind TEXT NOT NULL,
    impact_level TEXT NOT NULL,
    rationale TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('candidate', 'accepted', 'rejected', 'uncertain')),
    payload_json TEXT NOT NULL
);

CREATE TABLE snapshots (
    snapshot_id TEXT PRIMARY KEY,
    version_id TEXT NOT NULL,
    version_label TEXT NOT NULL,
    published_at TEXT NOT NULL,
    data_cutoff_at TEXT NOT NULL,
    snapshot_sha256 TEXT NOT NULL
);

-- 检索文档与 FTS5 词项（与桌面端 persona_search_documents / persona_search_fts 同义）。
-- document_id 是 1..n 的插入序号（派生顺序固定，见 retrieval/SPEC.md §2），
-- 与 search_fts 的 rowid 一一对应；document_id 不参与 logical_sha256。
CREATE TABLE search_docs (
    document_id INTEGER PRIMARY KEY,
    document_key TEXT NOT NULL UNIQUE,
    document_kind TEXT NOT NULL CHECK (document_kind IN ('source', 'claim', 'theme', 'method')),
    source_id TEXT REFERENCES sources(source_id),
    claim_id TEXT REFERENCES claims(claim_id),
    theme_id TEXT REFERENCES themes(theme_id),
    source_status TEXT NOT NULL CHECK (source_status IN ('active', 'inactive')),
    title TEXT NOT NULL,
    body TEXT NOT NULL
);

CREATE VIRTUAL TABLE search_fts USING fts5(
    title,
    body,
    tokenize='trigram'
);