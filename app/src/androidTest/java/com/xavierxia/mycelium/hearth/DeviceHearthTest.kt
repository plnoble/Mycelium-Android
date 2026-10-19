package com.xavierxia.mycelium.hearth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The hearth file is SQLCipher. A known character sheet and dialogue must not appear in the
 * bytes on disk. The passphrase is a test value, not a user key.
 */
@RunWith(AndroidJUnit4::class)
class DeviceHearthTest {
    @Test
    fun plaintextCharacterAndDialogueAreAbsentFromTheDatabaseFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "hearth-cipher").apply { mkdirs() }
        val file = File(directory, "hearth.sqlite")
        if (file.exists()) file.delete()
        System.loadLibrary("sqlcipher")
        val sheet = "明文人物设定不应出现在加密库里"
        val dialogue = "明文对话句子也不应出现"
        val session = { SqlCipherHearthSession(SQLiteDatabase.openOrCreateDatabase(file, "test-passphrase", null, null)) }
        val row = CharacterStore(session).create(CharacterDraft(name = "老周", personality = sheet), "t")
        ConversationLog(session).append(row.id, null, dialogue, "回答也不应出现", "本机 · 测试模型", "t")

        val bytes = file.readBytes()
        assertTrue(bytes.size > sheet.toByteArray().size)
        // Compare in byte space (one byte maps to one char), so the CJK sentences can hit.
        val latin = String(bytes, Charsets.ISO_8859_1)
        assertFalse("库文件还是明文 sqlite", latin.startsWith("SQLite format 3"))
        assertFalse(latin.contains(String(sheet.toByteArray(), Charsets.ISO_8859_1)))
        assertFalse(latin.contains(String(dialogue.toByteArray(), Charsets.ISO_8859_1)))
    }
}