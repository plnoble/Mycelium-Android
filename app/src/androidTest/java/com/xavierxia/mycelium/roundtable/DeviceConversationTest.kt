package com.xavierxia.mycelium.roundtable

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The conversation file is SQLCipher. A known sentence must not appear in the bytes on disk.
 * The passphrase is a test value, not a user key.
 */
@RunWith(AndroidJUnit4::class)
class DeviceConversationTest {
    @Test
    fun plaintextDialogueIsAbsentFromTheDatabaseFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "conversation-cipher").apply { mkdirs() }
        val file = File(directory, "conversations.sqlite")
        if (file.exists()) file.delete()
        System.loadLibrary("sqlcipher")
        val sentence = "明文对话句子不应出现在库文件里"
        val database = SQLiteDatabase.openOrCreateDatabase(file, "test-passphrase", null, null)
        try {
            val session = object : SqlSession {
                override fun exec(sql: String, args: List<Any?>) {
                    if (args.isEmpty()) database.execSQL(sql) else database.execSQL(sql, args.toTypedArray())
                }

                override fun query(sql: String, args: List<Any?>): List<List<Any?>> = emptyList()

                override fun close() = Unit
            }
            ConversationStore { session }.append(
                "persona",
                "version",
                null,
                sentence,
                "回答也不应出现",
                "[]",
                "DeepSeek",
                "deepseek-flash",
                "t",
            )
        } finally {
            database.close()
        }
        val bytes = file.readBytes()
        assertTrue(bytes.size > sentence.toByteArray().size)
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(sentence))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("回答也不应出现"))
    }
}
