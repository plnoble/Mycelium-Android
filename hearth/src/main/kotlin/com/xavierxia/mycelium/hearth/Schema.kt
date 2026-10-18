package com.xavierxia.mycelium.hearth

/**
 * Tables for the hearth space. The table definitions are `IF NOT EXISTS`, so opening the same file
 * twice is harmless. Columns added after the first release are applied in one transaction together
 * with a marker in the `meta` table: either the whole upgrade happened or none of it did, and a
 * second session that races the first one sees the marker and does nothing.
 */
internal fun HearthSession.ensureHearthTables() {
    createTables()
    synchronized(schemaLock) { upgradeIfNeeded() }
}

/** How the characters table looks from [SCHEMA_VERSION] on. */
private const val SCHEMA_VERSION = 3
private const val SCHEMA_KEY = "schema-version"

/**
 * Serializes the upgrade inside this process. Two ViewModels can open sessions at the same moment;
 * `BEGIN IMMEDIATE` already protects the file, this keeps them from both starting the work.
 */
private val schemaLock = Any()

private fun HearthSession.createTables() {
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
            cloud_confirmed_service TEXT NOT NULL DEFAULT '',
            cloud_confirmed_target TEXT NOT NULL DEFAULT ''
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
}

/** Column check, table changes and the version marker, all in one transaction. */
private fun HearthSession.upgradeIfNeeded() {
    transaction {
        val stored = query("SELECT value FROM meta WHERE key = ?", listOf(SCHEMA_KEY))
            .singleOrNull()?.stringAt(0)?.toIntOrNull() ?: 0
        if (stored >= SCHEMA_VERSION) return@transaction
        val columns = query("PRAGMA table_info(characters)").map { it.stringAt(1) }.toSet()
        if ("model_kind" !in columns) {
            exec("ALTER TABLE characters ADD COLUMN model_kind TEXT NOT NULL DEFAULT 'default'")
            // Before the three-way choice a character pointed at one specific local model, and it
            // keeps doing that. Rows with no model id keep the default choice.
            exec("UPDATE characters SET model_kind = 'local' WHERE model_id <> ''")
        }
        if ("service_id" !in columns) exec("ALTER TABLE characters ADD COLUMN service_id TEXT NOT NULL DEFAULT ''")
        if ("service_name" !in columns) exec("ALTER TABLE characters ADD COLUMN service_name TEXT NOT NULL DEFAULT ''")
        if ("cloud_confirmed_service" !in columns) {
            exec("ALTER TABLE characters ADD COLUMN cloud_confirmed_service TEXT NOT NULL DEFAULT ''")
        }
        if ("cloud_confirmed_target" !in columns) {
            // What the user actually agreed to: the service id together with its endpoint, so a
            // changed address asks again. Older rows hold only a service id, which never matches a
            // target and therefore asks once more.
            exec("ALTER TABLE characters ADD COLUMN cloud_confirmed_target TEXT NOT NULL DEFAULT ''")
        }
        exec(
            "INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            listOf(SCHEMA_KEY, SCHEMA_VERSION.toString()),
        )
    }
}
