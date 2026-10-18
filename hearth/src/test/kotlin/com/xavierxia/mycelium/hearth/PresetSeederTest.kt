package com.xavierxia.mycelium.hearth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetSeederTest {
    private val session = memorySession()
    private val store = CharacterStore { session }
    private val seeder = PresetSeeder(store, HearthMeta { session })

    @Test
    fun presetsAreWrittenOnceAndDeletedOnesStayDeleted() {
        assertEquals(5, seeder.seedOnce("2026-10-18T10:00"))
        assertEquals(5, store.characters().size)
        assertEquals(0, seeder.seedOnce("2026-10-18T10:01"))
        assertEquals(5, store.characters().size)

        store.delete(requireNotNull(store.character("preset-ayuan")).id)
        assertEquals(0, seeder.seedOnce("2026-10-18T10:02"))
        assertNull(store.character("preset-ayuan"), "删掉的预设不该被自动加回来")
        assertEquals(4, store.characters().size)
    }

    @Test
    fun everyPresetCarriesTheSafetyLineAndDefaultsToTheDefaultLocalModel() {
        seeder.seedOnce("2026-10-18T10:00")
        val presets = presetCharacters().map { (id, _) -> requireNotNull(store.character(id)) }
        assertEquals(listOf("阿远", "年糕", "老K", "外婆", "老陈"), presets.map { it.name })
        presets.forEach { character ->
            assertEquals(ModelKind.DEFAULT, character.modelKind, "${character.name} 应该默认用默认本地模型")
            assertTrue(character.personality.contains(SAFETY_LINE), "${character.name} 的设定里缺安全兜底")
            assertTrue(systemPrompt(character).contains(SAFETY_LINE), "${character.name} 的提示里缺安全兜底")
            assertTrue(character.greeting.isNotBlank(), "${character.name} 缺开场白")
            assertTrue(character.background.isNotBlank(), "${character.name} 缺一句话简介")
        }
    }

    @Test
    fun aFailedFirstRunCanBeRetriedWithoutDuplicates() {
        // A row from an attempt that died before the flag was written.
        store.create("preset-ayuan", presetCharacters().first().second, "t", ignoreExisting = true)
        assertEquals(4, seeder.seedOnce("t"))
        assertEquals(5, store.characters().size)
        assertEquals(0, seeder.seedOnce("t"))
    }

    @Test
    fun aPresetIsAnOrdinaryCharacter() {
        seeder.seedOnce("t")
        val ayuan = requireNotNull(store.character("preset-ayuan"))
        store.update(ayuan.id, draftOf(ayuan).copy(name = "阿远（改）", personality = "我自己写的"), "t2")
        val edited = requireNotNull(store.character("preset-ayuan"))
        assertEquals("阿远（改）", edited.name)
        assertEquals("我自己写的", edited.personality)
    }
}
