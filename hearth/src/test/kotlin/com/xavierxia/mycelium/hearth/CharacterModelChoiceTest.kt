package com.xavierxia.mycelium.hearth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CharacterModelChoiceTest {
    private val session = memorySession()
    private val store = CharacterStore { session }

    @Test
    fun aHalfUpgradedDatabaseFinishesTheUpgradeOnTheNextOpen() {
        // A database that got the first new column but not the rest: opening it must end up with
        // all of them, and the characters already in it must survive.
        session.exec(
            """
            CREATE TABLE characters (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, personality TEXT NOT NULL, speech TEXT NOT NULL,
                background TEXT NOT NULL, relationship TEXT NOT NULL, greeting TEXT NOT NULL,
                model_id TEXT NOT NULL, model_name TEXT NOT NULL, temperature TEXT NOT NULL,
                max_tokens INTEGER NOT NULL, color INTEGER NOT NULL, updated_at TEXT NOT NULL,
                avatar_char TEXT NOT NULL, model_kind TEXT NOT NULL DEFAULT 'default'
            )
            """.trimIndent(),
        )
        session.exec(
            "INSERT INTO characters (id, name, personality, speech, background, relationship, greeting, " +
                "model_id, model_name, temperature, max_tokens, color, updated_at, avatar_char, model_kind) " +
                "VALUES ('half-1', '半升级', '', '', '', '', '', 'sha-half', 'Qwen', '0.7', 256, 0, 't', '半', 'local')",
        )
        val upgraded = requireNotNull(store.character("half-1"))
        assertEquals("半升级", upgraded.name)
        assertEquals(ModelKind.LOCAL, upgraded.modelKind)
        assertEquals("sha-half", upgraded.modelId)
        assertEquals("", upgraded.cloudConfirmedTarget)
        // Opening again changes nothing.
        assertEquals("半升级", requireNotNull(store.character("half-1")).name)
    }

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
                cloudConfirmedTarget = cloudTarget("svc-1", "https://api.example.net/v1/"),
            ),
            "t",
        )
        val readCloud = requireNotNull(store.character(cloud.id))
        assertEquals(ModelKind.CLOUD, readCloud.modelKind)
        assertEquals("svc-1", readCloud.serviceId)
        assertEquals("测试服务", readCloud.serviceName)
        assertEquals("deepseek-flash", readCloud.modelName)
        // The agreement is stored normalized, so a trailing slash never asks the user again.
        assertEquals(cloudTarget("svc-1", "https://api.example.net/v1"), readCloud.cloudConfirmedTarget)
    }

    @Test
    fun aCopyNeverInheritsTheCloudAgreement() {
        val source = store.create(
            CharacterDraft(
                name = "甲",
                modelKind = ModelKind.CLOUD,
                serviceId = "svc-1",
                serviceName = "测试服务",
                modelName = "deepseek-flash",
                cloudConfirmedTarget = cloudTarget("svc-1", "https://api.example.net"),
            ),
            "t",
        )
        val copied = store.copy(source.id, "t2")
        assertEquals("", copied.cloudConfirmedTarget)
    }

    @Test
    fun onlyARealMoveChangesTheCloudTarget() {
        assertEquals(cloudTarget("a", "https://api.example.net"), cloudTarget("a", "HTTPS://API.example.net:443/"))
        assertEquals(cloudTarget("a", "http://127.0.0.1:80"), cloudTarget("a", "http://127.0.0.1/"))
        // Another service, another host, another port or another path all count as a new receiver.
        assertTrue(cloudTarget("a", "https://api.example.net") != cloudTarget("b", "https://api.example.net"))
        assertTrue(cloudTarget("a", "https://api.example.net") != cloudTarget("a", "https://api.example.org"))
        assertTrue(cloudTarget("a", "https://api.example.net:8443") != cloudTarget("a", "https://api.example.net"))
        assertTrue(cloudTarget("a", "https://api.example.net/v2") != cloudTarget("a", "https://api.example.net/v1"))
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
        assertEquals("", upgraded.cloudConfirmedTarget)
        assertTrue(store.characters().isNotEmpty())
    }
}
