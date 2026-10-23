package com.xavierxia.mycelium.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-1d 的 S2/S3 **前置测例**：原生侧的「存」与「取」要等方案审（方案第 6 节写明了），但
 * 「这一轮用不用检查点、不用时原因码是几」是纯判断，现在就该钉住。
 *
 * 钉它的理由：这几个原因码会随统计一路报到真机日志与交付数据里。写错了不会让模型答错话，但会让
 * 「为什么没提速」的结论整个解释错——而 S5 的真机读数正是靠这些码来归因的。
 */
class CheckpointPlanTest {

    private fun store(vararg entries: Triple<String, Int, Int>): CheckpointStore {
        val store = CheckpointStore()
        entries.forEach { (key, pos, fill) -> store.put(key, pos, ByteArray(fill) { 1 }) }
        return store
    }

    private val key = "qwen35-4b|人物A|会话1"

    @Test
    fun aHybridModelWithAUsableCheckpointRestoresFromIt() {
        // 「每键只留最新一份」之后，同一键写两次只剩后写的那份；这里直接给一份可用的。
        val store = store(Triple(key, 1116, 32))

        val plan = planPrefix(store, key, maxPos = 1200, enabled = true, checkpointable = true)

        assertTrue("混合模型有可用检查点就该用它，实际 $plan", plan is PrefixPlan.Restore)
        assertEquals("就用这份", 1116, (plan as PrefixPlan.Restore).checkpoint.pos)
    }

    @Test
    fun aCheckpointPastTheSharedPrefixIsNotUsed() {
        // 位置超过本轮公共前缀的那份不能用（恢复出来位置就是错的）。
        val store = store(Triple(key, 1300, 32))

        val plan = planPrefix(store, key, maxPos = 1200, enabled = true, checkpointable = true)

        assertTrue("超出公共前缀 ⇒ 退回整段重读，实际 $plan", plan is PrefixPlan.FullReread)
        assertEquals("原因码要是 5", 5L, (plan as PrefixPlan.FullReread).reason!!.code)
    }

    @Test
    fun withoutAUsableCheckpointItFallsBackWithReasonFive() {
        val empty = planPrefix(store(), key, maxPos = 1200, enabled = true, checkpointable = true)
        assertTrue("没存过就要整段重读，实际 $empty", empty is PrefixPlan.FullReread)
        assertEquals("原因码要是 5（没有能用的检查点）", CheckpointFallback.NoUsableCheckpoint, (empty as PrefixPlan.FullReread).reason)
        assertEquals(5L, empty.reason!!.code)

        // 存过，但位置都超过了这一轮的公共前缀：同样算「没有能用的」。
        val tooFar = planPrefix(store(Triple(key, 1500, 32)), key, maxPos = 1200, enabled = true, checkpointable = true)
        assertEquals(
            "位置超过公共前缀的检查点不能用（用了会答非所问）",
            CheckpointFallback.NoUsableCheckpoint,
            (tooFar as PrefixPlan.FullReread).reason,
        )
    }

    @Test
    fun theSwitchIsCheckedBeforeTheStore() {
        // 开关关着：即使仓库里有一份完全可用的检查点，也必须整段重读（原因 7）。
        val plan = planPrefix(store(Triple(key, 1116, 32)), key, maxPos = 1200, enabled = false, checkpointable = true)

        assertTrue("开关关着不许走检查点，实际 $plan", plan is PrefixPlan.FullReread)
        assertEquals("原因码要是 7（开关关着）", CheckpointFallback.SwitchedOff, (plan as PrefixPlan.FullReread).reason)
    }

    @Test
    fun aPureAttentionModelGoesBackToTheLegacyPathWhateverTheSwitchSays() {
        // 纯注意力模型：**先按适用性分流**，之后才看开关。所以开关开着、关着都走 P2-1c 既有路径，
        // 而且都不该占用 5/6/7（方案审必修项 4：第一版把开关放在最前面，于是「纯注意力 + 开关关着」
        // 会返回 7，与「不占新码」的口径冲突）。
        listOf(true, false).forEach { enabled ->
            val plan = planPrefix(store(Triple(key, 1116, 32)), key, maxPos = 1200, enabled = enabled, checkpointable = false)

            assertTrue("开关=$enabled 时也必须是既有路径，实际 $plan", plan is PrefixPlan.LegacyPrefixCache)
        }
    }

    @Test
    fun theLegacyPathIsNotTheSameAsBeingForcedToRereadEverything() {
        // 方案审必修项 4 的另一半：纯注意力走 P2-1c，而 P2-1c 会**正常复用、裁剪注意力 KV**。
        // 所以它既不是「强制全量重读」（FullReread），也不该被解释成禁用前缀缓存。
        val plan = planPrefix(store(Triple(key, 1116, 32)), key, maxPos = 1200, enabled = true, checkpointable = false)

        assertTrue("纯注意力路径必须是 LegacyPrefixCache，实际 $plan", plan is PrefixPlan.LegacyPrefixCache)
        assertTrue("它不该是「强制重读」", plan !is PrefixPlan.FullReread)
    }

    @Test
    fun aFailedRestoreUsesReasonSix() {
        assertEquals("恢复失败的原因码要固定是 6", CheckpointFallback.RestoreFailed, fallbackAfterRestoreFailure())
        assertEquals(6L, fallbackAfterRestoreFailure().code)
    }

    @Test
    fun theThreeNewReasonCodesDoNotCollideWithTheOldOnes() {
        val codes = CheckpointFallback.entries.map { it.code }

        assertEquals("新原因码就这三个", 3, codes.size)
        assertEquals("编号必须是 5/6/7（4 在 P2-1c 里已作废，不许复用）", listOf(5L, 6L, 7L), codes.sorted())
        assertTrue("0–4 属于 P2-1c", codes.none { it <= 4L })
        assertEquals("每个码都要能翻回来", CheckpointFallback.entries.toSet(),
            codes.mapNotNull { checkpointFallbackOf(it) }.toSet())
        assertNull("P2-1c 的码不许被这里认领", checkpointFallbackOf(4))
        assertNull(checkpointFallbackOf(0))
    }

    @Test
    fun everyReasonCodeHasAReadableNoteForTheDeviceLog() {
        CheckpointFallback.entries.forEach { fallback ->
            assertTrue("原因 ${fallback.code} 要有一句人话：${fallback.note}", fallback.note.isNotBlank())
        }
        assertEquals(
            "三个原因的说明要互不相同（真机日志里才分得清是哪种没提速）",
            3,
            CheckpointFallback.entries.map { it.note }.toSet().size,
        )
    }
}