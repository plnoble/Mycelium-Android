package com.xavierxia.mycelium.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 固定切法（全量审 R2 P1-1 修正版）：**切法只由 token 序列决定**，且必须跨轮稳定——
 * 冷读与「从上一轮落点续读」在共同前缀上要经历完全相同的批次。
 */
class PromptSlicingTest {

    @Test
    fun `resuming from an earlier save point keeps the shared prefix in the same batches`() {
        // 真实续聊条件（第一版漏掉的）：第 1 轮落点 53，第 2 轮落点 80，恢复自 53。
        val cuts = listOf(53, 80)
        val cold = PromptSlicing.batches(count = 87, cuts = cuts, nBatch = 512)
        val resumed = PromptSlicing.batches(count = 87, cuts = cuts, nBatch = 512, restoredFrom = 53)
        assertEquals(listOf(53, 27, 7), cold)
        assertEquals(listOf(27, 7), resumed)
        // 共同前缀 [53,87) 在两条路上批次完全一致：冷读的尾两批 == 续读的全部批次。
        assertEquals("续读必须是冷读的后缀", cold.drop(1), resumed)
    }

    @Test
    fun `the cut is only the message boundary not the save point`() {
        // 只有单点（本轮保存点 80）时，共同前缀 [53,80) 会被并进第一批——这正是第一版的缺陷。
        assertEquals(listOf(80, 7), PromptSlicing.batches(count = 87, cut = 80, nBatch = 512))
        // 取全部消息边界后，[53,80) 与 [80,87) 各自成批。
        assertEquals(listOf(53, 27, 7), PromptSlicing.batches(count = 87, cuts = listOf(53, 80), nBatch = 512))
    }

    @Test
    fun `without cuts the prompt is plain n_batch chunks`() {
        assertEquals(listOf(32, 32, 23), PromptSlicing.batches(count = 87, cut = 0, nBatch = 32))
    }

    @Test
    fun `both segments restart the n_batch count from their own head`() {
        // n_batch=32、断点 53：[32,21] 到断点，尾段 [32,2]。
        assertEquals(listOf(32, 21, 32, 2), PromptSlicing.batches(count = 87, cut = 53, nBatch = 32))
    }

    @Test
    fun `a long conversation stays aligned across all boundaries`() {
        // 跨 512 token：边界 300/700/1100，n_batch 512。
        val cuts = listOf(300, 700, 1100)
        assertEquals(listOf(300, 400, 400, 100), PromptSlicing.batches(count = 1200, cuts = cuts, nBatch = 512))
        // 恢复自 700（上一轮落点）⇒ 与冷读从 700 起完全一致。
        assertEquals(
            PromptSlicing.batches(count = 1200, cuts = cuts, nBatch = 512).drop(2),
            PromptSlicing.batches(count = 1200, cuts = cuts, nBatch = 512, restoredFrom = 700),
        )
    }

    @Test
    fun `the slicing never depends on the key or the store`() {
        // trim 之后冷读、rekey 之后续读，切法都不变——它只由 count 与 cuts 决定。
        val cuts = listOf(53, 80)
        val first = PromptSlicing.batches(count = 87, cuts = cuts, nBatch = 512)
        repeat(3) { assertEquals(first, PromptSlicing.batches(count = 87, cuts = cuts, nBatch = 512)) }
    }

    @Test
    fun `the cut never produces an empty batch`() {
        assertEquals(listOf(60), PromptSlicing.batches(count = 60, cut = 60, nBatch = 512))
        assertEquals(listOf(60), PromptSlicing.batches(count = 60, cut = 0, nBatch = 512))
        // 越界或重复的断点被忽略。
        assertEquals(listOf(60), PromptSlicing.batches(count = 60, cuts = listOf(-5, 0, 60, 99, 60), nBatch = 512))
    }
}
