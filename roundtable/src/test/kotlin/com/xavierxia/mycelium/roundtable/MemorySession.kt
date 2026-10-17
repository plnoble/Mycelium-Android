package com.xavierxia.mycelium.roundtable

import java.sql.DriverManager

/** Plain sqlite for history behaviour. Encryption is asserted on the device SQLCipher file. */
class MemorySession : SqlSession {
    private val connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    override fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty()) {
            connection.createStatement().use { it.execute(sql) }
        } else {
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    override fun query(sql: String, args: List<Any?>): List<List<Any?>> {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows ->
                val width = rows.metaData.columnCount
                return buildList {
                    while (rows.next()) {
                        add(List(width) { column -> rows.getString(column + 1) })
                    }
                }
            }
        }
    }

    override fun close() = Unit
}
