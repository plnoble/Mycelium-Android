package com.xavierxia.mycelium.llm

/**
 * 固定切法（P2-1d；全量审 R2 P1-1 修正版）。
 *
 * 规则：读提示一律在**每条消息结束处**（用户消息结束、助手头之前）切开——这些位置只由 token 序列
 * 决定，因此**跨轮稳定**。每段内再从段首起每 [nBatch] 个一批。[restoredFrom] 之前的部分由检查点
 * 载入、不参与预填。
 *
 * 关键点（第一版漏掉的）：恢复组从**上一轮**保存点起步，而本轮保存点通常更靠后。若只在「本轮保存点」
 * 切一刀，共同前缀在两条路上仍会经历不同批次（浮点形状差异照旧）。取全部消息边界之后，
 * 「冷读 [0,count)」与「恢复 [restoredFrom,count)」在共同前缀上的批次完全一致。
 */
object PromptSlicing {

    /** 多断点版本：返回每批的 token 数（按顺序）。 */
    fun batches(count: Int, cuts: List<Int>, nBatch: Int, restoredFrom: Int = -1): List<Int> {
        require(count > 0) { "提示必须非空" }
        require(nBatch > 0) { "批次大小必须为正" }
        val ordered = cuts.filter { it > 0 && it < count }.distinct().sorted()
        val start = restoredFrom.coerceIn(0, count)
        val result = mutableListOf<Int>()
        var pos = start
        while (pos < count) {
            val plain = minOf(nBatch, count - pos)
            val nextCut = ordered.firstOrNull { it > pos }
            val length = if (nextCut != null && pos + plain > nextCut) nextCut - pos else plain
            result += length
            pos += length
        }
        return result
    }

    /** 单断点便捷入口（等价于 [cuts] 只有一个元素）。 */
    fun batches(count: Int, cut: Int, nBatch: Int, restoredFrom: Int = -1): List<Int> =
        batches(count, listOf(cut), nBatch, restoredFrom)
}
