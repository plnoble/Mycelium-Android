package com.xavierxia.mycelium.roundtable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Plain-text stand-in for the keystore so the store rules are testable off-device. */
private class FakeSecrets : SecretBox {
    override fun wrap(plain: ByteArray): String = "wrapped:" + String(plain, Charsets.UTF_8)
    override fun unwrap(stored: String): ByteArray = stored.removePrefix("wrapped:").toByteArray(Charsets.UTF_8)
}

class CloudStoreTest {
    private var blob: String? = null
    private fun store() = CloudStore({ blob }, { blob = it }, FakeSecrets())

    @Test
    fun servicesCanBeAddedEditedAndRemoved() {
        val cloud = store()
        val deepseek = cloud.addService(null, "DeepSeek", "https://api.deepseek.com/", "sk-one")
        val second = cloud.addService(null, "自建", "https://api.example.net", "sk-two")
        assertEquals(listOf("DeepSeek", "自建"), cloud.services().map { it.name })
        assertEquals(listOf("https://api.deepseek.com", "https://api.example.net"), cloud.allBaseUrls())

        cloud.addService(second, "自建二号", "https://api.example.net", null)
        assertEquals("自建二号", cloud.services().single { it.id == second }.name)
        assertEquals("sk-two", cloud.apiKey(second), "空白 key 应当保留原来的")
        assertEquals("····-two", cloud.keyHint(second))

        cloud.addService(deepseek, "DeepSeek", "https://api.deepseek.com", "sk-new")
        assertEquals("sk-new", cloud.apiKey(deepseek))

        cloud.removeService(second)
        assertEquals(listOf("DeepSeek"), cloud.services().map { it.name })
        assertNull(cloud.apiKey(second))
    }

    @Test
    fun nameAndAddressAreRequired() {
        val cloud = store()
        assertThrows(IllegalArgumentException::class.java) { cloud.addService(null, "  ", "https://a", null) }
        assertThrows(IllegalArgumentException::class.java) { cloud.addService(null, "服务", "  ", null) }
    }

    @Test
    fun multiSelectSavesTheTickedSubsetAndKeepsItOnTheNextPull() {
        val cloud = store()
        val id = cloud.addService(null, "假服务", "http://127.0.0.1:8765", "sk-test")
        cloud.saveModels(id, listOf("a", "b"))
        assertEquals(listOf("a", "b"), cloud.models(id))

        // A later pull ticks a different subset; the store follows the ticks.
        cloud.saveModels(id, listOf("b", "c"))
        assertEquals(listOf("b", "c"), cloud.models(id))

        // Pulling again and ticking nothing saves nothing but never removes the service.
        cloud.saveModels(id, emptyList())
        assertTrue(cloud.models(id).isEmpty())
        assertEquals(1, cloud.services().size)
    }

    @Test
    fun removalClearsThatServicesModelsAndSelectionOnly() {
        val cloud = store()
        val first = cloud.addService(null, "一", "https://a.example", "k1")
        val second = cloud.addService(null, "二", "https://b.example", "k2")
        cloud.saveModels(first, listOf("m1"))
        cloud.saveModels(second, listOf("m2"))
        cloud.rememberSelection(second, "m2")
        assertEquals(CloudSelection(second, "m2"), cloud.selection())

        cloud.removeService(second)
        assertTrue(cloud.models(second).isEmpty())
        assertEquals(listOf("m1"), cloud.models(first))
        assertNull(cloud.selection())
        assertEquals(1, cloud.services().size)
    }

    @Test
    fun selectionSurvivesAndFallsBackWhenItsModelIsDropped() {
        val cloud = store()
        val first = cloud.addService(null, "一", "https://a.example", "k1")
        val second = cloud.addService(null, "二", "https://b.example", "k2")
        cloud.saveModels(first, listOf("m1", "m2"))
        cloud.saveModels(second, listOf("n1"))
        cloud.rememberSelection(first, "m1")
        assertEquals(CloudSelection(first, "m1"), cloud.selection())

        // Saving a subset that keeps the selected model keeps the selection.
        cloud.saveModels(first, listOf("m1", "m2"))
        assertEquals(CloudSelection(first, "m1"), cloud.selection())

        // Dropping the selected model clears only the selection, not the other service's models.
        cloud.saveModels(first, listOf("m2"))
        assertNull(cloud.selection())
        assertEquals(listOf("n1"), cloud.models(second))

        // A service id that was never saved keeps what it had.
        cloud.rememberSelection(second, "n1")
        assertEquals(CloudSelection(second, "n1"), cloud.selection())
    }

    @Test
    fun savingAServiceKeepsEverythingElseIntact() {
        val cloud = store()
        val first = cloud.addService(null, "一", "https://a.example", "k1")
        val second = cloud.addService(null, "二", "https://b.example", "k2")
        cloud.saveModels(first, listOf("m1", "m2"))
        cloud.saveModels(second, listOf("n1"))
        cloud.rememberSelection(first, "m2")

        // Editing the first service's address must not disturb its key, its models, or the second.
        cloud.addService(first, "一改", "https://a2.example", null)
        assertEquals("k1", cloud.apiKey(first))
        assertEquals(listOf("m1", "m2"), cloud.models(first))
        assertEquals(listOf("n1"), cloud.models(second))
        assertEquals(CloudSelection(first, "m2"), cloud.selection())
        assertEquals(listOf("一改", "二"), cloud.services().map { it.name })
    }

    @Test
    fun aCorruptBlobDoesNotThrow() {
        blob = "{not json"
        val cloud = store()
        assertTrue(cloud.services().isEmpty())
        assertNull(cloud.selection())
        // The next write replaces the unusable blob.
        val id = cloud.addService(null, "新", "https://new.example", "kx")
        assertEquals("新", cloud.services().single().name)
        assertEquals("kx", cloud.apiKey(id))
    }
}
