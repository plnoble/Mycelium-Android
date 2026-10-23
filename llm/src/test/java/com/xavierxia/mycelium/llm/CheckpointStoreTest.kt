package com.xavierxia.mycelium.llm

import org.junit.Assert.assertEquals
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

    /** 句柄在测例里就是一个递增整数：真实实现里它是原生那份内存的编号。 */
    private var nextHandle = 1

    /** 存一份并返回它的句柄（大小按 [size] 记）。 */
    private fun CheckpointStore.putBytes(key: String, pos: Int, size: Int): Boolean =
        put(key, pos, nextHandle++, size.toLong())

    @Test
    fun withOnePerKeyOnlyTheLatestCheckpointSurvives() {
        // 2026-10-23 统筹裁定：每个缓存键只留最新一份（对话只前进，旧的没用且有内存代价）。
        val store = CheckpointStore()
        store.putBytes("k", pos = 100, size = 8)
        store.putBytes("k", pos = 400, size = 8)
        store.putBytes("k", pos = 700, size = 8)

        assertEquals("只留一份", 1, store.countFor("k"))
        assertEquals("留下的必须是最后存的那份", 700, store.find("k", maxPos = 100_000)?.pos)
        assertEquals("位置不超过公共前缀才可用", 700, store.find("k", maxPos = 700)?.pos)
        assertNull("前缀比它短就不能用（旧的那份已经不在仓库里了）", store.find("k", maxPos = 699))
    }

    @Test
    fun theSamePositionPrefersTheNewerCheckpoint() {
        val store = CheckpointStore()
        store.putBytes("k", pos = 500, size = 4)
        store.putBytes("k", pos = 500, size = 4)

        val picked = store.find("k", maxPos = 500)
        assertEquals(500, picked?.pos)
        assertEquals("同一位置要用后存的那份（序号更大）", 2, picked?.sequence?.toInt())
    }

    @Test
    fun anotherKeyIsNeverUsedForThisOne() {
        val store = CheckpointStore()
        store.putBytes("conversation-a", pos = 900, size = 8)

        assertNull("换会话就不能再拿上一段的检查点", store.find("conversation-b", maxPos = 1000))
    }

    @Test
    fun invalidatingOneKeyLeavesTheOtherAlone() {
        val store = CheckpointStore()
        store.putBytes("a", pos = 10, size = 8)
        store.putBytes("b", pos = 20, size = 8)

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
        store.putBytes("k", pos = 100, size = 8)
        store.putBytes("k", pos = 200, size = 8)
        store.putBytes("k", pos = 300, size = 8)
        store.putBytes("k", pos = 400, size = 8)

        assertEquals("每个键只留一份", 1, store.countFor("k"))
        assertNull("旧的那些不再留在仓库里（200 也走了）", store.find("k", maxPos = 250))
        assertEquals("只剩最后存的那份", 400, store.find("k", maxPos = 400)?.pos)
    }

    @Test
    fun evictsGloballyWhenTheTotalBudgetIsExceeded() {
        // 每份 40 字节、总上限 100 字节：存三份就会挤掉最旧的那份。
        val store = CheckpointStore(maxPerKey = 3, maxTotalBytes = 100)
        store.putBytes("a", pos = 10, size = 40)
        store.putBytes("b", pos = 20, size = 40)
        assertEquals(80L, store.totalBytes)

        store.putBytes("c", pos = 30, size = 40)
        assertTrue("总量必须回到上限以内：${store.totalBytes}", store.totalBytes <= 100)
        assertNull("最旧的 a 被全局淘汰", store.find("a", maxPos = 10))
        assertEquals(20, store.find("b", maxPos = 20)?.pos)
        assertEquals(30, store.find("c", maxPos = 30)?.pos)
    }

    @Test
    fun aCheckpointLargerThanTheWholeBudgetIsRefused() {
        val store = CheckpointStore(maxTotalBytes = 100)

        assertFalse("单份就超上限，不许塞进来", store.putBytes("k", pos = 10, size = 101))
        assertEquals(0, store.size)
        assertTrue("零字节也不收", !store.put("k", pos = 10, handle = 2, bytes = 0))
    }

    @Test
    fun droppingACheckpointReleasesItsNativeHandle() {
        // 字节归原生之后，「从仓库里移除」必须同时释放句柄，否则那块内存永远回不去。
        // 这条覆盖：同键覆盖、超量淘汰、分叉作废、整键作废、全部清空。
        val dropped = mutableListOf<Int>()
        val store = CheckpointStore(maxPerKey = 1, maxTotalBytes = 100, onDrop = { dropped += it })
        store.putBytes("k", pos = 10, size = 40)
        store.putBytes("k", pos = 20, size = 40)          // 同键覆盖：旧句柄要被释放
        assertEquals("同键覆盖必须释放旧句柄", 1, dropped.size)

        // 预算 100、每份 40：现在只有 1 份（40）。再放两份不同键（40+40=120 > 100）就会淘汰最旧的。
        store.putBytes("other", pos = 5, size = 40)
        store.putBytes("third", pos = 6, size = 40)
        assertTrue("超上限时淘汰最旧的（要释放它的句柄）", dropped.size >= 2)

        // 上面那次淘汰已经把最旧的 k 挤掉了，所以整键作废用一个**确定还在**的键来验。
        val before = dropped.size
        store.invalidate("third")
        assertEquals("整键作废之后该键为空", 0, store.countFor("third"))
        assertTrue("整键作废要释放", dropped.size > before)

        val remaining = store.size
        val beforeClear = dropped.size
        store.clear()
        assertEquals("clear() 之后仓库必须是空的", 0, store.size)
        assertEquals("clear() 要把剩下的每一份都释放掉", beforeClear + remaining, dropped.size)
    }

    @Test
    fun rekeyingKeepsTheHandleAndThePosition() {
        // 改挂是「同一个会话换了编号」，字节与位置都不该动，只换键名。
        val dropped = mutableListOf<Int>()
        val store = CheckpointStore(onDrop = { dropped += it })
        store.putBytes("temp-1", pos = 29, size = 8)
        val found = store.find("temp-1", maxPos = 100)
        store.rekey("temp-1", "conv-1")

        assertEquals("改挂不该释放句柄", 0, dropped.size)
        assertEquals("改挂后旧键应当是空的", null, store.find("temp-1", maxPos = 100))
        assertEquals("改挂后新键要拿到同一份（位置不变）", 29, store.find("conv-1", maxPos = 100)?.pos)
        assertEquals("句柄也必须原样保留", found?.handle, store.find("conv-1", maxPos = 100)?.handle)
    }

    // ---- 方案审必修项 3：同键分叉后，旧分支的检查点必须作废 -------------------------------

    @Test
    fun aCheckpointFromAnAbandonedBranchIsDropped() {
        // 场景：本轮在 400 分叉，仓库里还留着上一轮的 700。前缀长过 700 之后 find 会挑中 700，
        // 但那是**另一条分支**上的状态——恢复出来就答非所问。
        // 注意「每键只留最新一份」之后，700 写进去时 400 就已经被覆盖了；所以这里单独验证
        // invalidateBeyond 的语义：它必须把超过有效前缀的那份删掉，而不是留着。
        val store = CheckpointStore()
        store.putBytes("k", pos = 700, size = 8)

        store.invalidateBeyond("k", validPrefix = 400)

        assertEquals("超过有效前缀的那份必须被删掉", 0, store.countFor("k"))
        assertNull("删掉之后没有可用的检查点", store.find("k", maxPos = 1200))
    }

    @Test
    fun invalidatingBeyondKeepsTheOneAtThePrefix() {
        val store = CheckpointStore()
        store.putBytes("k", pos = 400, size = 8)

        store.invalidateBeyond("k", validPrefix = 400)

        assertEquals("等于有效前缀的那份要留下（它是可用的）", 1, store.countFor("k"))
        assertEquals(400, store.find("k", maxPos = 1200)?.pos)
    }

    @Test
    fun invalidatingBeyondOneKeyLeavesTheOtherKeysAlone() {
        val store = CheckpointStore()
        store.putBytes("a", pos = 700, size = 8)
        store.putBytes("b", pos = 700, size = 8)

        store.invalidateBeyond("a", validPrefix = 400)

        assertEquals("只动作废的那个键", 0, store.countFor("a"))
        assertEquals("别的键不受影响", 1, store.countFor("b"))
    }

    @Test
    fun invalidatingBeyondAnUnknownKeyIsHarmless() {
        val store = CheckpointStore()
        store.putBytes("a", pos = 700, size = 8)

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
        // 单份就是真机实测的 99,026,860 字节（1412 token 的整份状态）；这里只需要大小，不造真字节。
        val one = 99_026_860L
        var handle = 1
        assertTrue(store.put("a", 10, handle++, one))
        assertTrue(store.put("b", 20, handle++, one))
        assertTrue(store.put("c", 30, handle++, one))
        assertEquals("三份都在", 3, store.size)
        assertTrue("没超预算：${store.totalBytes}", store.totalBytes <= CheckpointStore.MAX_TOTAL_BYTES)

        // 第四份会超上限 ⇒ 淘汰最旧的，且总量回到预算内。
        assertTrue(store.put("d", 40, handle++, one))
        assertTrue("总量必须回到上限以内：${store.totalBytes}", store.totalBytes <= CheckpointStore.MAX_TOTAL_BYTES)
        assertNull("最旧的 a 被挤掉", store.find("a", maxPos = 10))
        assertEquals(40, store.find("d", maxPos = 40)?.pos)
    }

    @Test
    fun evictingTheCurrentKeyDuringGlobalEvictionDoesNotLoseTheIncomingOne() {
        // 全量审必修项 9 回归：maxPerKey=3、预算 100，依次写 a:40、b:40、a:40。
        // 写第三次时 a 既是当前键也是最旧的，旧 a 被全局淘汰时不许把新 a 丢掉。
        val store = CheckpointStore(maxPerKey = 3, maxTotalBytes = 100)
        store.putBytes("a", pos = 10, size = 40)
        store.putBytes("b", pos = 20, size = 40)
        assertTrue("新 a 必须存下", store.putBytes("a", pos = 30, size = 40))

        assertEquals("新 a 必须查得到（不能因为旧 a 被清而丢掉）", 30, store.find("a", maxPos = 100)?.pos)
        assertEquals("b 还在", 20, store.find("b", maxPos = 100)?.pos)
    }

    @Test
    fun replacingAKeyDoesNotEvictOtherKeysFirst() {
        // R2 非阻断退化回归：maxPerKey=1、预算 100，写 A:40、B:40、B:40（同键覆盖）。
        // 第一版先算全局再处理本键，旧 B 还算在总量里 ⇒ 80+40>100 把 A 挤掉了。A 不该被删。
        val store = CheckpointStore(maxPerKey = 1, maxTotalBytes = 100)
        store.putBytes("A", pos = 10, size = 40)
        store.putBytes("B", pos = 20, size = 40)
        assertTrue("同键覆盖要成功", store.putBytes("B", pos = 30, size = 40))

        assertEquals("B 换成新的那份", 30, store.find("B", maxPos = 100)?.pos)
        assertEquals("A 不该被同键覆盖牵连删除", 10, store.find("A", maxPos = 100)?.pos)
    }
}
