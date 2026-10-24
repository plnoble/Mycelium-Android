package com.xavierxia.mycelium.llm

/**
 * 固定切法（P2-1d，任务书 2026-10-24 第一节第 2 条）。
 *
 * 规则：读提示一律在「用户消息结束、助手头之前」（[cut]，即 `checkpointPositionFor` 的落点）切一刀；
 * [0, cut) 段从段首起每 [nBatch] 个一批；[cut, count) 是尾巴批。**开、关、第一轮都用这套规则**，
 * 因此只要两条路的提示在共同前缀上一致，它们的批次形状在共同前缀上也完全一致——
 * 浮点末位对齐，温度 0 才可能逐字相同。native 的预填循环遵循同一规则
 * （落点必断 + 段内 n_batch），本函数是同一规则的可测表达。
 */
object PromptSlicing {

    /**
     * 返回每批的 token 数（按顺序）。
     *
     * @param count 本轮提示总 token 数。
     * @param cut 落点（0 或负 = 没有落点：整段从段首每 [nBatch] 个一批）。
     * @param restoredFrom 恢复位置（>=0 表示 [0, restoredFrom) 由检查点载入、不参与预填）。
     */
    fun batches(count: Int, cut: Int, nBatch: Int, restoredFrom: Int = -1): List<Int> {
        require(count > 0) { "提示必须非空" }
        require(nBatch > 0) { "批次大小必须为正" }
        val start = restoredFrom.coerceIn(0, count)
        val result = mutableListOf<Int>()
        var pos = start
        while (pos < count) {
            val plain = minOf(nBatch, count - pos)
            // 本批会越过落点：在落点处断开（只有还没到落点时才会发生）。
            val length = if (cut > pos && cut < count && pos + plain > cut) cut - pos else plain
            result += length
            pos += length
        }
        return result
    }
}
