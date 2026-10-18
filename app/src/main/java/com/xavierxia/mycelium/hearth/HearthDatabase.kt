package com.xavierxia.mycelium.hearth

import android.content.Context
import android.database.Cursor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The hearth database is its own SQLCipher file, separate from the roundtable one. The passphrase
 * is random and wrapped by an AndroidKeyStore key; clearing app data deletes both.
 */
class HearthDatabase(context: Context) {
    private val file = File(context.filesDir, "hearth/hearth.sqlite")
    private val preferences = context.getSharedPreferences("hearth-database", Context.MODE_PRIVATE)
    private val keystore = HearthKeystore("mycelium-hearth")

    fun session(): HearthSession {
        System.loadLibrary("sqlcipher")
        file.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(file, passphrase(), null, null)
        return SqlCipherHearthSession(database)
    }

    private fun passphrase(): String {
        val stored = preferences.getString("passphrase", null)
        if (stored != null) return String(keystore.unwrap(stored), Charsets.UTF_8)
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val passphrase = bytes.joinToString("") { "%02x".format(it) }
        preferences.edit().putString("passphrase", keystore.wrap(passphrase.toByteArray(Charsets.UTF_8))).apply()
        return passphrase
    }
}

/** One session over an open SQLCipher database. The caller decides when to close it. */
class SqlCipherHearthSession(private val database: SQLiteDatabase) : HearthSession {
    override fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty()) database.execSQL(sql) else database.execSQL(sql, args.toTypedArray())
    }

    override fun query(sql: String, args: List<Any?>): List<List<Any?>> {
        val cursor = if (args.isEmpty()) database.rawQuery(sql, emptyArray()) else database.rawQuery(sql, *args.toTypedArray())
        return cursor.use { rows ->
            buildList {
                while (rows.moveToNext()) add(List(rows.columnCount) { column -> rows.valueAt(column) })
            }
        }
    }

    override fun close() = database.close()
}

private fun Cursor.valueAt(column: Int): Any? = when (getType(column)) {
    Cursor.FIELD_TYPE_NULL -> null
    Cursor.FIELD_TYPE_INTEGER -> getLong(column)
    Cursor.FIELD_TYPE_FLOAT -> getDouble(column)
    Cursor.FIELD_TYPE_BLOB -> String(getBlob(column), Charsets.UTF_8)
    else -> getString(column)
}

/** Wraps short secrets with an AndroidKeyStore AES-GCM key. The key never leaves the keystore. */
private class HearthKeystore(private val alias: String) {
    fun wrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(plain)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun unwrap(stored: String): ByteArray {
        val payload = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        return cipher.doFinal(payload.copyOfRange(12, payload.size))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = store.getKey(alias, null) as SecretKey?
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}