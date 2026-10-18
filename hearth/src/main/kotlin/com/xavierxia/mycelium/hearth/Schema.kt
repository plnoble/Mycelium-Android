package com.xavierxia.mycelium.hearth

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
            updated_at TEXT NOT NULL
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
}