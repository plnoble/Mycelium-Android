package com.xavierxia.mycelium.hearth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CharacterModelChoiceTest {
    private val session = memorySession()
    private val store = CharacterStore { session }

    @Test
    fun theThreeModelChoicesRoundTrip() {
        val byDefault = store.create(CharacterDraft(name = "甲"), "t")
        assertEquals(ModelKind.DEFAULT, requireNotNull(store.character(byDefault.id)).modelKind)

        val local = store.create(
            CharacterDraft(name = "乙", modelKind = ModelKind.LOCAL, modelId = "sha-1", modelName = "Qwen3.5-4B"),
            "t",
        )
        val readLocal = requireNotNull(store.character(local.id))
        assertEquals(ModelKind.LOCAL, readLocal.modelKind)
        assertEquals("sha-1", readLocal.modelId)
        assertEquals("Qwen3.5-4B", readLocal.modelName)

        val cloud = store.create(
            CharacterDraft(
                name = "丙",
                modelKind = ModelKind.CLOUD,
                serviceId = "svc-1",
                serviceName = "测试服务",
                modelName = "deepseek-flash",
                cloudConfirmedService = "svc-1",
            ),
            "t",
        )
        val readCloud = requireNotNull(store.character(cloud.id))
        assertEquals(ModelKind.CLOUD, readCloud.modelKind)
        assertEquals("svc-1", readCloud.serviceId)
        assertEquals("测试服务", readCloud.serviceName)
        assertEquals("deepseek-flash", readCloud.modelName)
        assertEquals("svc-1", readCloud.cloudConfirmedService)
    }

    @Test
    fun aChoiceWithoutItsTargetIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            store.create(CharacterDraft(name = "甲", modelKind = ModelKind.LOCAL), "t")
        }
        assertFailsWith<IllegalArgumentException> {
            store.create(CharacterDraft(name = "甲", modelKind = ModelKind.CLOUD, modelName = "m"), "t")
        }
    }

    @Test
    fun anOlderDatabaseKeepsItsCharactersAndLearnsTheNewColumns() {
        // The table as it looked before the three-way choice: 14 columns, no model_kind.
        session.exec(
            """
            CREATE TABLE characters (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, personality TEXT NOT NULL, speech TEXT NOT NULL,
                background TEXT NOT NULL, relationship TEXT NOT NULL, greeting TEXT NOT NULL,
                model_id TEXT NOT NULL, model_name TEXT NOT NULL, temperature TEXT NOT NULL,
                max_tokens INTEGER NOT NULL, color INTEGER NOT NULL, updated_at TEXT NOT NULL, avatar_char TEXT NOT NULL
            )
            """.trimIndent(),
        )
        session.exec(
            "INSERT INTO characters VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                "old-1", "老周", "话少", "", "", "", "在吗", "sha-old", "Qwen3.5-4B",
                "0.7", 256, 0, "2026-10-17T10:00", "周",
            ),
        )
        // Every store call opens a session, and opening a session runs the upgrade.
        val upgraded = requireNotNull(store.character("old-1"))
        assertEquals("老周", upgraded.name)
        assertEquals("sha-old", upgraded.modelId)
        // It used to point at one specific local model, so it keeps doing that.
        assertEquals(ModelKind.LOCAL, upgraded.modelKind)
        assertEquals("", upgraded.serviceId)
        assertEquals("", upgraded.cloudConfirmedService)
        assertTrue(store.characters().isNotEmpty())
    }
}
