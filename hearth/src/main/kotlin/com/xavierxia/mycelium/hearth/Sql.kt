package com.xavierxia.mycelium.hearth

/**
 * One database session. The caller opens it; this module never sees the passphrase and never
 * opens a file itself. On the phone the app plugs in SQLCipher; JVM tests plug in plain sqlite.
 */
interface HearthSession : AutoCloseable {
    fun exec(sql: String, args: List<Any?> = emptyList())

    fun query(sql: String, args: List<Any?> = emptyList()): List<List<Any?>>
}

internal fun List<Any?>.stringAt(index: Int): String =
    requireNotNull(this[index]) { "第 $index 列是空的" } as String

internal fun List<Any?>.numberAt(index: Int): Number =
    requireNotNull(this[index]) { "第 $index 列是空的" } as Number

/** Opens a session, ensures the tables, runs the block, then closes. */
internal inline fun <T> withSession(connect: () -> HearthSession, block: (HearthSession) -> T): T =
    connect().use { session ->
        session.ensureHearthTables()
        block(session)
    }