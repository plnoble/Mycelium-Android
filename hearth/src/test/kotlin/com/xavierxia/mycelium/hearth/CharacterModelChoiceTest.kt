package com.xavierxia.mycelium.hearth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun aDatabaseThatDiedAfterOnlyAddingTheColumnKeepsTheModelItPointedAt() {
        // The real shape of the interrupted old migration: the column landed, the finished marker
        // never did, and the row therefore still carries the column default instead of the choice
        // the user made. Character `cut-1` pointed at model B; `cut-2` never pointed anywhere.
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
        session.exec("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        session.exec(
            "INSERT INTO characters (id, name, personality, speech, background, relationship, greeting, " +
                "model_id, model_name, temperature, max_tokens, color, updated_at, avatar_char, model_kind) " +
                "VALUES ('cut-1', '中断的甲', '', '', '', '', '', 'sha-B', 'Qwen3.5-4B', '0.7', 256, 0, 't', '甲', 'default')",
        )
        session.exec(
            "INSERT INTO characters (id, name, personality, speech, background, relationship, greeting, " +
                "model_id, model_name, temperature, max_tokens, color, updated_at, avatar_char, model_kind) " +
                "VALUES ('cut-2', '中断的乙', '', '', '', '', '', '', '', '0.7', 256, 0, 't', '乙', 'default')",
        )
        assertTrue(session.query("SELECT value FROM meta WHERE key = 'schema-version'").isEmpty())

        // Opening the app finishes that migration instead of trusting the column that is already there.
        val repaired = requireNotNull(store.character("cut-1"))
        assertEquals(ModelKind.LOCAL, repaired.modelKind)
        assertEquals("sha-B", repaired.modelId)
        assertEquals("Qwen3.5-4B", repaired.modelName)
        assertEquals("", repaired.serviceId)
        assertEquals("", repaired.cloudConfirmedTarget)
        // A row that never pointed at a model keeps the default choice.
        assertEquals(ModelKind.DEFAULT, requireNotNull(store.character("cut-2")).modelKind)
        // The marker is written, and opening again changes nothing.
        assertTrue(session.query("SELECT value FROM meta WHERE key = 'schema-version'").isNotEmpty())
        assertEquals(ModelKind.LOCAL, requireNotNull(store.character("cut-1")).modelKind)
    }

    @Test
    fun aFinishedUpgradeKeepsADefaultTheUserChoseEvenWithAnOldModelIdOnTheRow() {
        // A database the upgrade already finished: 14 columns, no model_kind, and a character that
        // used to point at model B.
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
                "marked-1", "已升级", "", "", "", "", "", "sha-B", "Qwen3.5-4B",
                "0.7", 256, 0, "2026-10-17T10:00", "升",
            ),
        )
        // The first open runs the upgrade and leaves the marker behind.
        assertEquals(ModelKind.LOCAL, requireNotNull(store.character("marked-1")).modelKind)
        assertTrue(session.query("SELECT value FROM meta WHERE key = 'schema-version'").isNotEmpty())

        // The user now picks "follow the default" in the editor; the row keeps its old model id.
        val chosen = draftOf(requireNotNull(store.character("marked-1"))).copy(modelKind = ModelKind.DEFAULT)
        store.update("marked-1", chosen, "2026-10-18T09:00")
        val raw = session.query("SELECT model_kind, model_id FROM characters WHERE id = 'marked-1'").single()
        assertEquals("default", raw.stringAt(0))
        assertEquals("sha-B", raw.stringAt(1))

        // A later open must read that as the user's choice, not as the interrupted state above.
        val reopened = requireNotNull(store.character("marked-1"))
        assertEquals(ModelKind.DEFAULT, reopened.modelKind)
        assertEquals("sha-B", reopened.modelId)
    }

    @Test
    fun onlyAnUnmarkedDatabaseReadsTheColumnDefaultAsAChoiceNobodyMade() {
        // 3 is the version that started writing the marker in the same transaction as the backfill.
        // At or above it the marker vouches for the column, so a `default` in it is the user's.
        assertFalse(legacyModelKindNeedsRepair(storedVersion = 3, modelKindPresent = true))
        assertFalse(legacyModelKindNeedsRepair(storedVersion = 4, modelKindPresent = true))
        // Below it — including no marker at all — nothing vouches for the column.
        assertTrue(legacyModelKindNeedsRepair(storedVersion = 2, modelKindPresent = true))
        assertTrue(legacyModelKindNeedsRepair(storedVersion = 0, modelKindPresent = true))
        // A column this run adds cannot hold anyone's choice, marker or not.
        assertTrue(legacyModelKindNeedsRepair(storedVersion = 3, modelKindPresent = false))
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
        // A query selects the receiver too, and it is case sensitive: no silent merging.
        assertTrue(
            cloudTarget("a", "https://api.example.net?tenant=A") !=
                cloudTarget("a", "https://api.example.net?tenant=a"),
        )
        assertEquals(
            cloudTarget("a", "https://api.example.net?tenant=A"),
            cloudTarget("a", "HTTPS://API.example.net:443/?tenant=A"),
        )
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
