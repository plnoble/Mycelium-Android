package com.xavierxia.mycelium.llm

/**
 * 只给真机诊断用：限制预填批次大小（0 = 默认 512）。
 *
 * 用途：把「整段重读」切成与「恢复后只读尾部」相同的批次形状，用来区分温度 0 的差异是**批次形状**
 * 造成的浮点末位，还是**状态恢复**不完全一致。`NativeBridge` 是 internal，app 模块看不见，故包一层。
 */
object NativePromptBatch {
    fun set(size: Int) {
        NativeBridge().setPromptBatch(size)
    }
}
