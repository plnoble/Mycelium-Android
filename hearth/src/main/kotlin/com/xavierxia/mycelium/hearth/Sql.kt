package com.xavierxia.mycelium.hearth

/**
 * One database session. The caller opens it; this module never sees the passphrase and never
 * opens a file itself. On the phone the app plugs in SQLCipher; JVM tests plug in plain sqlite.
 */
interface HearthSession : AutoCloseable {
    fun exec(sql: String, args: List<Any?> = emptyList())

    fun query(sql: String, args: List<Any?> = emptyList()): List<List<Any?>>

    /**
     * Runs [block] as one transaction. `BEGIN IMMEDIATE` takes the write lock straight away, so two
     * sessions opening the same file at the same time cannot both decide they need the same schema
     * change. Anything thrown rolls the whole block back, leaving no half-applied change behind.
     */
    fun <T> transaction(block: () -> T): T {
        exec("BEGIN IMMEDIATE")
        return try {
            val result = block()
            exec("COMMIT")
            result
        } catch (failure: Throwable) {
            runCatching { exec("ROLLBACK") }
            throw failure
        }
    }
}

internal fun List<Any?>.stringAt(index: Int): String =
    requireNotNull(this[index]) { "第 $index 列是空的" } as String

internal fun List<Any?>.textOrNullAt(index: Int): String? = this[index] as String?

internal fun List<Any?>.numberAt(index: Int): Number =
    requireNotNull(this[index]) { "第 $index 列是空的" } as Number

/** Opens a session, ensures the tables, runs the block, then closes. */
internal inline fun <T> withSession(connect: () -> HearthSession, block: (HearthSession) -> T): T =
    connect().use { session ->
        session.ensureHearthTables()
        block(session)
    }
