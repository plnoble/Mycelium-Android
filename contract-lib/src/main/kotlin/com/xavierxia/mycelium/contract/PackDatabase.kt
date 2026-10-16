package com.xavierxia.mycelium.contract

import org.sqlite.SQLiteConfig
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Read-only access to a persona pack's SQLite file.
 *
 * The SQL itself is the contract (`retrieval/SPEC.md`); opening the file is the only thing that
 * changes between a JVM test (sqlite-jdbc) and the app (the SQLite chosen by P0-7).
 */
interface PackDatabase {
    fun <T> read(block: (PackQuery) -> T): T
}

interface PackQuery {
    fun query(sql: String, bind: (PackStatement) -> Unit = {}): List<PackRow>
}

interface PackStatement {
    fun setString(index: Int, value: String)
    fun setLong(index: Int, value: Long)
}

class PackRow(private val values: List<Any?>) {
    fun text(index: Int): String = values[index] as String

    fun textOrNull(index: Int): String? = values[index] as String?

    fun long(index: Int): Long = (values[index] as Number).toLong()

    fun double(index: Int): Double = (values[index] as Number).toDouble()
}

/** Opens a pack database with sqlite-jdbc. The connection is read-only and closed with [read]. */
class SqliteJdbcPackDatabase(private val path: String) : PackDatabase {
    override fun <T> read(block: (PackQuery) -> T): T {
        val config = SQLiteConfig().apply { setReadOnly(true) }
        DriverManager.getConnection("jdbc:sqlite:$path", config.toProperties()).use { connection ->
            return block(JdbcQuery(connection))
        }
    }
}

private class JdbcQuery(private val connection: Connection) : PackQuery {
    override fun query(sql: String, bind: (PackStatement) -> Unit): List<PackRow> {
        connection.prepareStatement(sql).use { statement ->
            bind(JdbcStatement(statement))
            statement.executeQuery().use { result ->
                val rows = mutableListOf<PackRow>()
                val width = result.metaData.columnCount
                while (result.next()) {
                    rows += PackRow(List(width) { column -> result.valueAt(column + 1) })
                }
                return rows
            }
        }
    }
}

private class JdbcStatement(private val statement: java.sql.PreparedStatement) : PackStatement {
    override fun setString(index: Int, value: String) = statement.setString(index, value)

    override fun setLong(index: Int, value: Long) = statement.setLong(index, value)
}

private fun ResultSet.valueAt(column: Int): Any? = when (val value = getObject(column)) {
    is ByteArray -> String(value, Charsets.UTF_8)
    else -> value
}
