package com.xavierxia.mycelium.llm

/**
 * P2-1d 新增的回退原因（方案 2.5）：接在 P2-1c 的 0–3 之后，**不复用 4**（4 在 P2-1c 里已经作废）。
 *
 * 这几个码会由原生随统计一起报回来，所以编号是跨层契约：改编号等于改协议。
 */
internal enum class CheckpointFallback(val code: Long, val note: String) {
    /** 这一轮没有能用的检查点（没存过、位置对不上、或者已经被作废）。 */
    NoUsableCheckpoint(5, "没有能用的检查点"),

    /** 检查点取出来了，但原生恢复失败（`set_data_ext` 或 `seq_rm` 没成）——退回整段重读。 */
    RestoreFailed(6, "检查点恢复失败"),

    /** 开关关着（设置里的总开关，或这台机器的模型不适用后的手动关闭）。 */
    SwitchedOff(7, "检查点开关关着"),
}

/** 把原生报回来的原因码翻回枚举；0–4 属于 P2-1c 的语义，这里只认 5–7。 */
internal fun checkpointFallbackOf(code: Long): CheckpointFallback? =
    CheckpointFallback.entries.firstOrNull { it.code == code }

/**
 * 这一轮走哪条路（P2-1d 的 Kotlin 侧契约）。
 *
 * **S2/S3 原生侧的存与取要等方案审**（方案第 6 节），但「用不用检查点、不用时原因码是几」是纯判断，
 * 现在就能钉住：写错了不会答错话，但会让真机读数失去意义。
 */
internal sealed interface PrefixPlan {
    /** 用检查点：提示的前 [Restore.checkpoint.pos] 个 token 不必再读，从那里接着读。 */
    data class Restore(val checkpoint: Checkpoint) : PrefixPlan

    /**
     * 整段重读，行为等于 P2-1c。
     *
     * [reason] 为 null 表示「这条路本来就不适用」，**不该**为它编一个新原因码：那些码的含义是
     * 「本该用检查点却没用到」，硬套会把之后的真机读数解释错。
     */
    data class FullReread(val reason: CheckpointFallback?, val note: String) : PrefixPlan

    /**
     * **走 P2-1c 的既有路径**（方案审必修项 4）。
     *
     * 纯注意力模型**不是**「强制全量重读」：P2-1c 会正常复用、裁剪注意力 KV。把它塞进
     * [FullReread] 会让人误以为这条路被禁用了，所以单独给一个结果。
     */
    data object LegacyPrefixCache : PrefixPlan
}

/**
 * 决定这一轮用不用检查点。**顺序即优先级，别调换**（方案审必修项 4）：
 *
 *  1. **先按模型适用性分流**：不是混合/循环模型 ⇒ 走 P2-1c 既有路径（[PrefixPlan.LegacyPrefixCache]），
 *     这里**不碰开关、不碰仓库、不占新原因码**——它压根不是「本该用却没用到」；
 *  2. 开关关着 ⇒ 原因 7（纯注意力模型早就走开了，剩下这些才受开关管）；
 *  3. 仓库里没有「位置不超过 [maxPos]」的检查点 ⇒ 原因 5；
 *  4. 否则用位置最大的那一份（同位置取较新的，由仓库负责）。
 *
 * 第一版把开关放在最前面，于是「纯注意力 + 开关关着」会返回 7，与「不占新码」的口径冲突。
 *
 * @param maxPos 这一轮的公共前缀长度：只能用位置不超过它的检查点。
 * @param enabled 用户的总开关。
 * @param checkpointable 模型是不是混合/循环（`llama_model_is_hybrid || llama_model_is_recurrent`）。
 */
internal fun planPrefix(
    store: CheckpointStore,
    key: String,
    maxPos: Int,
    enabled: Boolean,
    checkpointable: Boolean,
): PrefixPlan {
    if (!checkpointable) return PrefixPlan.LegacyPrefixCache
    if (!enabled) {
        return PrefixPlan.FullReread(CheckpointFallback.SwitchedOff, CheckpointFallback.SwitchedOff.note)
    }
    val checkpoint = store.find(key, maxPos)
        ?: return PrefixPlan.FullReread(CheckpointFallback.NoUsableCheckpoint, CheckpointFallback.NoUsableCheckpoint.note)
    return PrefixPlan.Restore(checkpoint)
}

/**
 * 原生报了「恢复失败」之后要用的回退原因（S3）。
 *
 * 单独给一个函数是为了让这个决定**只有一个出处**：原生那边返回假之后，编排不能自己挑原因码。
 */
internal fun fallbackAfterRestoreFailure(): CheckpointFallback = CheckpointFallback.RestoreFailed