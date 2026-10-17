package com.xavierxia.mycelium.roundtable

import android.database.Cursor
import com.xavierxia.mycelium.contract.PackDatabase
import com.xavierxia.mycelium.contract.PackQuery
import com.xavierxia.mycelium.contract.PackRow
import com.xavierxia.mycelium.contract.PackStatement
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Read-only SQLCipher handle for a persona pack.
 * The pack is public derived data, so the passphrase is empty; SQLCipher is used because the
 * system SQLite has no FTS5. Writes are refused by OPEN_READONLY.
 */
class SqlCipherPackDatabase(private val path: String) : PackDatabase {
    override fun <T> read(block: (PackQuery) -> T): T {
        System.loadLibrary("sqlcipher")
        val database = SQLiteDatabase.openDatabase(
            path,
            ByteArray(0),
            null,
            SQLiteDatabase.OPEN_READONLY,
            null,
            null,
        )
        try {
            return block(SqlCipherQuery(database))
        } finally {
            database.close()
        }
    }
}

private class SqlCipherQuery(private val database: SQLiteDatabase) : PackQuery {
    override fun query(sql: String, bind: (PackStatement) -> Unit): List<PackRow> {
        val arguments = SqlArgumentList()
        bind(CollectingStatement(arguments))
        // rawQuery is Java varargs. The array must be spread, or it is bound as parameter 1.
        database.rawQuery(sql, *arguments.toTypedArray()).use { cursor ->
            val rows = mutableListOf<PackRow>()
            while (cursor.moveToNext()) {
                rows += PackRow(List(cursor.columnCount) { column -> cursor.valueAt(column) })
            }
            return rows
        }
    }
}

/** Collects 1-based SQL parameters into the array `rawQuery` expects. */
internal class SqlArgumentList {
    private val values = mutableListOf<Any?>()

    fun setString(index: Int, value: String) = put(index, value)

    fun setLong(index: Int, value: Long) = put(index, value)

    fun toTypedArray(): Array<Any?> = values.toTypedArray()

    /** Index 1 is the first array slot. Setting the same index again replaces the value. */
    private fun put(index: Int, value: Any?) {
        require(index >= 1) { "SQL 参数从 1 开始，实际是 $index" }
        while (values.size < index - 1) values += null
        if (values.size == index - 1) values += value else values[index - 1] = value
    }
}

private class CollectingStatement(private val arguments: SqlArgumentList) : PackStatement {
    override fun setString(index: Int, value: String) = arguments.setString(index, value)

    override fun setLong(index: Int, value: Long) = arguments.setLong(index, value)
}

private fun Cursor.valueAt(column: Int): Any? = when (getType(column)) {
    Cursor.FIELD_TYPE_NULL -> null
    Cursor.FIELD_TYPE_INTEGER -> getLong(column)
    Cursor.FIELD_TYPE_FLOAT -> getDouble(column)
    Cursor.FIELD_TYPE_BLOB -> String(getBlob(column), Charsets.UTF_8)
    else -> getString(column)
}
