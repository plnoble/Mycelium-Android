package com.xavierxia.mycelium.hearth

/**
 * Tables for the hearth space. Columns added after the first release are appended to an existing
 * database with ALTER TABLE, so an install that already has characters keeps every row.
 */
internal fun HearthSession.ensureHearthTables() {
    exec(
        """
        CREATE TABLE IF NOT EXISTS characters (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            personality TEXT NOT NULL,
            speech TEXT NOT NULL,
            background TEXT NOT NULL,
            relationship TEXT NOT NULL,
            greeting TEXT NOT NULL,
            model_id TEXT NOT NULL,
            model_name TEXT NOT NULL,
            temperature TEXT NOT NULL,
            max_tokens INTEGER NOT NULL,
            color INTEGER NOT NULL,
            updated_at TEXT NOT NULL,
            avatar_char TEXT NOT NULL,
            model_kind TEXT NOT NULL DEFAULT 'default',
            service_id TEXT NOT NULL DEFAULT '',
            service_name TEXT NOT NULL DEFAULT '',
            cloud_confirmed_service TEXT NOT NULL DEFAULT ''
        )
        """.trimIndent(),
    )
    exec(
        """
        CREATE TABLE IF NOT EXISTS conversations (
            id TEXT PRIMARY KEY,
            character_id TEXT NOT NULL,
            title TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """.trimIndent(),
    )
    exec(
        """
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            conversation_id TEXT NOT NULL,
            role TEXT NOT NULL,
            text TEXT NOT NULL,
            model TEXT
        )
        """.trimIndent(),
    )
    exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    upgradeCharacters()
}

/** Adds the columns a database written by an earlier build is missing. Nothing is dropped. */
private fun HearthSession.upgradeCharacters() {
    val columns = query("PRAGMA table_info(characters)").map { it.stringAt(1) }.toSet()
    if ("model_kind" !in columns) {
        exec("ALTER TABLE characters ADD COLUMN model_kind TEXT NOT NULL DEFAULT 'default'")
        // Before the three-way choice every character pointed at one specific local model.
        exec("UPDATE characters SET model_kind = 'local' WHERE model_id <> ''")
    }
    if ("service_id" !in columns) exec("ALTER TABLE characters ADD COLUMN service_id TEXT NOT NULL DEFAULT ''")
    if ("service_name" !in columns) exec("ALTER TABLE characters ADD COLUMN service_name TEXT NOT NULL DEFAULT ''")
    if ("cloud_confirmed_service" !in columns) {
        exec("ALTER TABLE characters ADD COLUMN cloud_confirmed_service TEXT NOT NULL DEFAULT ''")
    }
}
