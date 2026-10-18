package com.xavierxia.mycelium.roundtable

/** One SQLCipher session. The caller opens it; this module never sees the passphrase. */
interface SqlSession : AutoCloseable {
    fun exec(sql: String, args: List<Any?> = emptyList())

    fun query(sql: String, args: List<Any?> = emptyList()): List<List<Any?>>
}
