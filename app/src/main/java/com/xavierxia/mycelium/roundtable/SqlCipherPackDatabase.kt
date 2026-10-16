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
        val values = mutableListOf<Any?>()
        bind(CollectingStatement(values))
        database.rawQuery(sql, values.toTypedArray()).use { cursor ->
            val rows = mutableListOf<PackRow>()
            while (cursor.moveToNext()) {
                rows += PackRow(List(cursor.columnCount) { column -> cursor.valueAt(column) })
            }
            return rows
        }
    }
}

private class CollectingStatement(private val values: MutableList<Any?>) : PackStatement {
    override fun setString(index: Int, value: String) {
        put(index, value)
    }

    override fun setLong(index: Int, value: Long) {
        put(index, value)
    }

    private fun put(index: Int, value: Any?) {
        while (values.size < index) values += null
        if (values.size == index) values += value else values[index - 1] = value
    }
}

private fun Cursor.valueAt(column: Int): Any? = when (getType(column)) {
    Cursor.FIELD_TYPE_NULL -> null
    Cursor.FIELD_TYPE_INTEGER -> getLong(column)
    Cursor.FIELD_TYPE_FLOAT -> getDouble(column)
    Cursor.FIELD_TYPE_BLOB -> String(getBlob(column), Charsets.UTF_8)
    else -> getString(column)
}
