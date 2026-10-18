package com.xavierxia.mycelium.hearth

import java.sql.Connection
import java.sql.DriverManager

/**
 * Plain sqlite behind the same session interface the app implements with SQLCipher.
 * One instance owns one connection; close() is a no-op so a test can share it across calls,
 * like the app-side session keeps its database open.
 */
internal class JdbcSession(url: String) : HearthSession {
    private val connection: Connection = DriverManager.getConnection(url)

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
                    while (rows.next()) add(List(width) { column -> rows.getObject(column + 1) })
                }
            }
        }
    }

    override fun close() = Unit
}

internal fun memorySession(): HearthSession = JdbcSession("jdbc:sqlite::memory:")

internal fun fileSession(path: String): HearthSession = JdbcSession("jdbc:sqlite:$path")