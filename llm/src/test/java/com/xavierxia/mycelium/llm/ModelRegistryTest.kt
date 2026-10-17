package com.xavierxia.mycelium.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelRegistryTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Documents(val original: File, val direct: Boolean = true) : ModelDocuments {
        var retained = false
        override fun describe(uri: String) = DocumentDetails("test.gguf", original.length())
        override fun retain(uri: String): DocumentGrant {
            retained = true
            return DocumentGrant(true, true)
        }
        override fun supportsDirectRead(uri: String) = direct
        override fun open(uri: String) = original.inputStream()
        override fun release(uri: String) { retained = false }
    }
    private fun original() = temporary.newFile().apply { writeBytes("GGUF".toByteArray() + ByteArray(200) { it.toByte() }) }

    @Test fun directImportDeduplicatesPersistsAndRemovalPreservesOriginal() = runBlocking {
        val source = original()
        val before = source.readBytes()
        val documents = Documents(source)
        val directory = temporary.newFolder()
        val registry = ModelRegistry(directory, documents)
        val progress = mutableListOf<ImportProgress>()
        val added = registry.import("one", progress = { progress += it })
        assertFalse(added.duplicate)
        assertFalse(added.model.copied)
        assertEquals(64, added.model.sha256.length)
        assertEquals(source.length(), progress.last().bytes)
        assertTrue(progress.zipWithNext().all { it.first.bytes <= it.second.bytes })
        assertTrue(registry.import("one").duplicate)
        assertTrue(registry.import("same-bytes-at-another-uri").duplicate)
        assertEquals(1, ModelRegistry(directory, documents).list().size)
        registry.remove(added.model)
        assertTrue(registry.list().isEmpty())
        assertArrayEquals(before, source.readBytes())
    }
    @Test fun copyNeedsConsentAndOnlyPrivateCopyIsRemoved() = runBlocking {
        val source = original()
        val before = source.readBytes()
        val documents = Documents(source, direct = false)
        val directory = temporary.newFolder()
        val registry = ModelRegistry(directory, documents)
        try { registry.import("one"); fail("copy without consent") } catch (expected: CopyRequired) {
            assertEquals(source.length(), expected.document.size)
        }
        assertTrue(directory.listFiles()!!.isEmpty())
        assertFalse(documents.retained)
        val added = registry.import("one", allowCopy = true).model
        assertTrue(added.copied)
        assertArrayEquals(before, registry.copyFile(added).readBytes())
        registry.remove(added)
        assertFalse(registry.copyFile(added).exists())
        assertArrayEquals(before, source.readBytes())
    }
    @Test fun cancelledCopyLeavesNoRegistrationOrTemporaryFile() = runBlocking {
        val source = original()
        val documents = Documents(source, false)
        val directory = temporary.newFolder()
        val registry = ModelRegistry(directory, documents)
        try {
            registry.import("one", true) { if (it.bytes > 0) throw CancellationException("stop") }
            fail("must cancel")
        } catch (_: CancellationException) { }
        assertTrue(registry.list().isEmpty())
        assertTrue(directory.listFiles()!!.isEmpty())
        assertTrue(source.exists())
        assertFalse(documents.retained)
    }
    @Test fun invalidGgufDoesNotGetRegistered() = runBlocking {
        val source = temporary.newFile().apply { writeText("not a model") }
        val documents = Documents(source)
        val registry = ModelRegistry(temporary.newFolder(), documents)
        try { registry.import("bad"); fail("bad magic accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(registry.list().isEmpty())
        assertFalse(documents.retained)
    }
}

