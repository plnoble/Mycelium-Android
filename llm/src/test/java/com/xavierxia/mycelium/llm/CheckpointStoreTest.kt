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
    fun withOnePerKeyOnlyTheLatestCheckpointSurvives() {
        // 2026-10-23 统筹裁定：每个缓存键只留最新一份（对话只前进，旧的没用且有内存代价）。
        val store = CheckpointStore()
        store.put("k", pos = 100, bytes(8))
        store.put("k", pos = 400, bytes(8))
        store.put("k", pos = 700, bytes(8))

        assertEquals("只留一份", 1, store.countFor("k"))
        assertEquals("留下的必须是最后存的那份", 700, store.find("k", maxPos = 100_000)?.pos)
        assertEquals("位置不超过公共前缀才可用", 700, store.find("k", maxPos = 700)?.pos)
        assertNull("前缀比它短就不能用（旧的那份已经不在仓库里了）", store.find("k", maxPos = 699))
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
    fun theNewestCheckpointReplacesThePreviousOne() {
        // 统筹裁定「每键只留最新一份」之后的行为：位置 100..400 连存四次，只剩 400。
        val store = CheckpointStore()
        store.put("k", pos = 100, bytes(8))
        store.put("k", pos = 200, bytes(8))
        store.put("k", pos = 300, bytes(8))
        store.put("k", pos = 400, bytes(8))

        assertEquals("每个键只留一份", 1, store.countFor("k"))
        assertNull("旧的那些不再留在仓库里（200 也走了）", store.find("k", maxPos = 250))
        assertEquals("只剩最后存的那份", 400, store.find("k", maxPos = 400)?.pos)
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
        // 注意「每键只留最新一份」之后，700 写进去时 400 就已经被覆盖了；所以这里单独验证
        // invalidateBeyond 的语义：它必须把超过有效前缀的那份删掉，而不是留着。
        val store = CheckpointStore()
        store.put("k", pos = 700, bytes(8))

        store.invalidateBeyond("k", validPrefix = 400)

        assertEquals("超过有效前缀的那份必须被删掉", 0, store.countFor("k"))
        assertNull("删掉之后没有可用的检查点", store.find("k", maxPos = 1200))
    }

    @Test
    fun invalidatingBeyondKeepsTheOneAtThePrefix() {
        val store = CheckpointStore()
        store.put("k", pos = 400, bytes(8))

        store.invalidateBeyond("k", validPrefix = 400)

        assertEquals("等于有效前缀的那份要留下（它是可用的）", 1, store.countFor("k"))
        assertEquals(400, store.find("k", maxPos = 1200)?.pos)
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

    // ---- 2026-10-23 统筹裁定：整份状态、每键一份、总上限 300 MiB ---------------------------

    @Test
    fun theDefaultsMatchTheDecisionTakenFromTheMeasuredSize() {
        // 实测整份 99026860 字节（1412 token）：每键一份避免同会话重复占内存，300 MiB 约合三份。
        assertEquals("每键只留最新一份", 1, CheckpointStore.MAX_PER_KEY)
        assertEquals("总上限 300 MiB", 300L * 1024 * 1024, CheckpointStore.MAX_TOTAL_BYTES)
        val measured = 99_026_860L
        assertTrue("三份实测大小的快照必须放得下：${measured * 3} <= ${CheckpointStore.MAX_TOTAL_BYTES}",
            measured * 3 <= CheckpointStore.MAX_TOTAL_BYTES)
        assertTrue("但也只剩一点点余量（不许按「六份」估）：${measured * 4} > ${CheckpointStore.MAX_TOTAL_BYTES}",
            measured * 4 > CheckpointStore.MAX_TOTAL_BYTES)
    }

    @Test
    fun aFullStateSizedCheckpointFitsAndAFourthOneEvictsTheFirst() {
        // 用「实测大小」的假字节验证预算：三份正好装得下，第四份进来会挤掉最旧的。
        val store = CheckpointStore()
        val one = bytes(99_026_860, fill = 5)
        assertTrue(store.put("a", 10, one))
        assertTrue(store.put("b", 20, one))
        assertTrue(store.put("c", 30, one))
        assertEquals("三份都在", 3, store.size)
        assertTrue("没超预算：${store.totalBytes}", store.totalBytes <= CheckpointStore.MAX_TOTAL_BYTES)

        // 第四份会超上限 ⇒ 淘汰最旧的，且总量回到预算内。
        assertTrue(store.put("d", 40, one))
        assertTrue("总量必须回到上限以内：${store.totalBytes}", store.totalBytes <= CheckpointStore.MAX_TOTAL_BYTES)
        assertNull("最旧的 a 被挤掉", store.find("a", maxPos = 10))
        assertEquals(40, store.find("d", maxPos = 40)?.pos)
    }

    @Test
    fun evictingTheCurrentKeyDuringGlobalEvictionDoesNotLoseTheIncomingOne() {
        // 全量审必修项 9 回归：maxPerKey=3、预算 100，依次写 a:40、b:40、a:40。
        // 写第三次时 a 既是当前键也是最旧的，旧 a 被全局淘汰时不许把新 a 丢掉。
        val store = CheckpointStore(maxPerKey = 3, maxTotalBytes = 100)
        store.put("a", 10, bytes(40))
        store.put("b", 20, bytes(40))
        assertTrue("新 a 必须存下", store.put("a", 30, bytes(40)))

        assertEquals("新 a 必须查得到（不能因为旧 a 被清而丢掉）", 30, store.find("a", maxPos = 100)?.pos)
        assertEquals("b 还在", 20, store.find("b", maxPos = 100)?.pos)
    }
}
