package com.xavierxia.mycelium.llm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-1d 的 S1：检查点仓库。对照任务书验收里「本侧能证明」的那几条：
 *
 *  1. 选中「位置不超过公共前缀」的最新检查点；
 *  2. 没有可用的就返回 null（调用方整段重读，回退原因 5）；
 *  3. 换缓存键 / 换模型 / 停止 / 出错要作废；
 *  4. 超过份数或总占用上限时淘汰最旧的；
 *  5. 单份就超过总上限的，不许塞进来。
 *
 * 「恢复失败就整段重读」属于原生侧的 S3（假原生层证明不了状态对不对），不在这一组里。
 */
class CheckpointStoreTest {

    private fun bytes(size: Int, fill: Byte = 1) = ByteArray(size) { fill }

    @Test
    fun picksTheCheckpointClosestToTheSharedPrefix() {
        val store = CheckpointStore()
        store.put("k", pos = 100, bytes(8))
        store.put("k", pos = 400, bytes(8))
        store.put("k", pos = 700, bytes(8))

        assertEquals("公共前缀 700 时正好用 700 那份", 700, store.find("k", maxPos = 700)?.pos)
        assertEquals("前缀 699 时退到 400", 400, store.find("k", maxPos = 699)?.pos)
        assertEquals("前缀只有 50 时没有可用的", null, store.find("k", maxPos = 50))
        assertEquals("前缀很大也只挑最大的那份", 700, store.find("k", maxPos = 100_000)?.pos)
    }

    @Test
    fun theSamePositionPrefersTheNewerCheckpoint() {
        val store = CheckpointStore()
        store.put("k", pos = 500, bytes(4, fill = 1))
        store.put("k", pos = 500, bytes(4, fill = 2))

        val picked = store.find("k", maxPos = 500)
        assertEquals(500, picked?.pos)
        assertArrayEquals("同一位置要用后存的那份", bytes(4, fill = 2), picked?.bytes)
    }

    @Test
    fun anotherKeyIsNeverUsedForThisOne() {
        val store = CheckpointStore()
        store.put("conversation-a", pos = 900, bytes(8))

        assertNull("换会话就不能再拿上一段的检查点", store.find("conversation-b", maxPos = 1000))
    }

    @Test
    fun invalidatingOneKeyLeavesTheOtherAlone() {
        val store = CheckpointStore()
        store.put("a", pos = 10, bytes(8))
        store.put("b", pos = 20, bytes(8))

        store.invalidate("a")
        assertNull(store.find("a", maxPos = 100))
        assertEquals(20, store.find("b", maxPos = 100)?.pos)

        store.clear()
        assertEquals("全清以后一份都不许剩", 0, store.size)
        assertEquals(0L, store.totalBytes)
        assertNull(store.find("b", maxPos = 100))
    }

    @Test
    fun keepsAtMostThreePerKeyAndDropsTheOldest() {
        val store = CheckpointStore()
        store.put("k", pos = 100, bytes(8))
        store.put("k", pos = 200, bytes(8))
        store.put("k", pos = 300, bytes(8))
        store.put("k", pos = 400, bytes(8))

        assertEquals("每个键最多三份", 3, store.countFor("k"))
        assertNull("最旧的那份（位置 100）被淘汰", store.find("k", maxPos = 150))
        assertEquals(200, store.find("k", maxPos = 250)?.pos)
    }

    @Test
    fun evictsGloballyWhenTheTotalBudgetIsExceeded() {
        // 每份 40 字节、总上限 100 字节：存三份就会挤掉最旧的那份。
        val store = CheckpointStore(maxPerKey = 3, maxTotalBytes = 100)
        store.put("a", pos = 10, bytes(40))
        store.put("b", pos = 20, bytes(40))
        assertEquals(80L, store.totalBytes)

        store.put("c", pos = 30, bytes(40))
        assertTrue("总量必须回到上限以内：${store.totalBytes}", store.totalBytes <= 100)
        assertNull("最旧的 a 被全局淘汰", store.find("a", maxPos = 10))
        assertEquals(20, store.find("b", maxPos = 20)?.pos)
        assertEquals(30, store.find("c", maxPos = 30)?.pos)
    }

    @Test
    fun aCheckpointLargerThanTheWholeBudgetIsRefused() {
        val store = CheckpointStore(maxTotalBytes = 100)

        assertFalse("单份就超上限，不许塞进来", store.put("k", pos = 10, bytes(101)))
        assertEquals(0, store.size)
        assertTrue("空字节也不收", !store.put("k", pos = 10, ByteArray(0)))
    }

    @Test
    fun theStoreCopiesTheBytesItKeeps() {
        val store = CheckpointStore()
        val source = bytes(4, fill = 7)
        store.put("k", pos = 10, source)
        source[0] = 99

        assertArrayEquals("存进来的字节必须自己留一份，调用方改了不能影响它", bytes(4, fill = 7), store.find("k", maxPos = 10)?.bytes)
    }

    // ---- 方案审必修项 3：同键分叉后，旧分支的检查点必须作废 -------------------------------

    @Test
    fun aCheckpointFromAnAbandonedBranchIsDropped() {
        // 场景：本轮在 400 分叉，仓库里还留着上一轮的 700。前缀长过 700 之后 find 会挑中 700，
        // 但那是**另一条分支**上的状态——恢复出来就答非所问。
        val store = CheckpointStore()
        store.put("k", pos = 400, bytes(8))
        store.put("k", pos = 700, bytes(8))

        store.invalidateBeyond("k", validPrefix = 400)

        assertTrue("700 那份必须真的被删掉", store.countFor("k") == 1)
        assertEquals("留下的那份位置不超过有效前缀", 400, store.find("k", maxPos = 1200)?.pos)
    }

    @Test
    fun invalidatingBeyondKeepsTheOnesAtOrBeforeThePrefix() {
        val store = CheckpointStore()
        store.put("k", pos = 100, bytes(8))
        store.put("k", pos = 400, bytes(8))
        store.put("k", pos = 700, bytes(8))

        store.invalidateBeyond("k", validPrefix = 400)

        assertEquals("等于有效前缀的那份要留下", 2, store.countFor("k"))
        assertEquals("挑出来的仍然是最靠近前缀的那份", 400, store.find("k", maxPos = 1200)?.pos)
    }

    @Test
    fun invalidatingBeyondOneKeyLeavesTheOtherKeysAlone() {
        val store = CheckpointStore()
        store.put("a", 700, bytes(8))
        store.put("b", 700, bytes(8))

        store.invalidateBeyond("a", validPrefix = 400)

        assertEquals("只动作废的那个键", 0, store.countFor("a"))
        assertEquals("别的键不受影响", 1, store.countFor("b"))
    }

    @Test
    fun invalidatingBeyondAnUnknownKeyIsHarmless() {
        val store = CheckpointStore()
        store.put("a", 700, bytes(8))

        store.invalidateBeyond("nope", validPrefix = 1)

        assertEquals(1, store.size)
    }
}
