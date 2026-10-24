package com.xavierxia.mycelium.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 固定切法（任务书 2026-10-24 第一节第 2 条）：**切法只由 token 序列决定**；
 * 开和关的切法在共同前缀上完全一致——这是温度 0 逐字对照能成立的前提。
 */
class PromptSlicingTest {

    @Test
    fun `the cut splits the prompt regardless of the switch`() {
        // 同一段 87 token 提示、落点 53：开（恢复）与关（整段重读）在共同前缀上的批次完全一致。
        val closed = PromptSlicing.batches(count = 87, cut = 53, nBatch = 512)
        assertEquals(listOf(53, 34), closed)
        // 恢复组：[0,53) 由检查点载入，预填只剩尾巴 [34] —— 与关闭组的最后一批相同。
        assertEquals(listOf(34), PromptSlicing.batches(count = 87, cut = 53, nBatch = 512, restoredFrom = 53))
    }

    @Test
    fun `without a cut the prompt is plain n_batch chunks`() {
        assertEquals(listOf(32, 32, 23), PromptSlicing.batches(count = 87, cut = 0, nBatch = 32))
    }

    @Test
    fun `both segments restart the n_batch count from their own head`() {
        // 任务书字面：「切开，段内从段首起每 n_batch 个一批」——两段各自从段首数。
        // 头段 [0,53)：[32,21]；尾段 [53,87) 长 34：[32,2]。恢复组从 53 起也是 [32,2]，对齐 ✓。
        assertEquals(listOf(32, 21, 32, 2), PromptSlicing.batches(count = 87, cut = 53, nBatch = 32))
        assertEquals(listOf(32, 2), PromptSlicing.batches(count = 87, cut = 53, nBatch = 32, restoredFrom = 53))
    }

    @Test
    fun `first round and restored round share the tail shape`() {
        // 第一轮（无恢复、读整段）与「恢复到同落点」在共同前缀上切法一致：
        // 第一轮 = [53] + [7]；恢复组从 53 起只有 [7]。
        assertEquals(listOf(53, 7), PromptSlicing.batches(count = 60, cut = 53, nBatch = 512))
        assertEquals(listOf(7), PromptSlicing.batches(count = 60, cut = 53, nBatch = 512, restoredFrom = 53))
    }

    @Test
    fun `the cut never produces an empty batch`() {
        // 落点正好在段首（cut=0 视为无落点；cut=count 视为没有尾巴）：不允许出现 0 长批次。
        assertEquals(listOf(60), PromptSlicing.batches(count = 60, cut = 60, nBatch = 512))
        assertEquals(listOf(60), PromptSlicing.batches(count = 60, cut = 0, nBatch = 512))
    }
}
