package com.xavierxia.mycelium.hearth

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Documents why the device test matters: the same sentences SQLCipher hides are readable in an
 * unencrypted file. The search runs in byte space (ISO-8859-1 maps one byte to one char), so a
 * CJK sentence can actually hit; comparing decoded text against the original string would never
 * match, and the check would pass no matter what the file contains.
 */
class PlaintextFileTest {
    @Test
    fun anUnencryptedFileContainsTheCharacterSheetAndTheDialogue() {
        val file = Files.createTempDirectory("hearth-plain").resolve("plain.sqlite").toFile()
        val sheet = "明文人物设定不应出现在加密库里"
        val dialogue = "明文对话句子也不应出现"
        val session = fileSession(file.path)
        val created = CharacterStore { session }.create(CharacterDraft(name = "老周", personality = sheet), "t")
        ConversationLog { session }.append(created.id, null, dialogue, "回答也不应出现", "t")

        val latin = String(file.readBytes(), Charsets.ISO_8859_1)
        assertTrue(latin.startsWith("SQLite format 3"))
        assertTrue(latin.contains(String(sheet.toByteArray(), Charsets.ISO_8859_1)))
        assertTrue(latin.contains(String(dialogue.toByteArray(), Charsets.ISO_8859_1)))
    }
}