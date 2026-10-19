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
 * The first version that wrote [SCHEMA_KEY] in the same transaction as `model_kind`. A stored marker
 * at least this high vouches for the column: the backfill below committed with it. A missing marker,
 * or one lower than this, vouches for nothing — the build before it added the column and could be
 * interrupted before the update, leaving every row on the column default. This constant describes
 * history, so it stays at 3 when [SCHEMA_VERSION] grows: a later upgrade must never rewrite a
 * `default` the user chose in an already marked database.
 */
private const val MODEL_KIND_SINCE = 3

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
        val modelKindPresent = "model_kind" in columns
        if (!modelKindPresent) {
            exec("ALTER TABLE characters ADD COLUMN model_kind TEXT NOT NULL DEFAULT 'default'")
        }
        // Before the three-way choice a character pointed at one specific local model, and it keeps
        // doing that. Rows with no model id keep the default choice. This also repairs the state the
        // pre-marker build left when it added the column and stopped before the update: the row is
        // still `default` only because that is the column default, not because anyone chose it. Once
        // the marker says the upgrade committed, `default` on a row is the user's own choice even
        // though an old model id is still sitting next to it, and it is left alone.
        if (legacyModelKindNeedsRepair(stored, modelKindPresent)) {
            exec("UPDATE characters SET model_kind = 'local' WHERE model_kind = 'default' AND model_id <> ''")
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

/**
 * Whether a `default` on a row has to be read back as the local model the user had picked. True for
 * a column this run just added — no row could hold a choice in it — and for a database whose marker
 * does not vouch for the backfill, which is the state the interrupted migration left behind. False
 * once [SCHEMA_KEY] is at least [MODEL_KIND_SINCE]: there the column was written by the finished
 * upgrade, so `default` is what the user chose and an old model id next to it is just a leftover.
 */
internal fun legacyModelKindNeedsRepair(storedVersion: Int, modelKindPresent: Boolean): Boolean =
    !modelKindPresent || storedVersion < MODEL_KIND_SINCE
