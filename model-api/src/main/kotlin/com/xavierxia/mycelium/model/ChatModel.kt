package com.xavierxia.mycelium.model
import kotlinx.coroutines.flow.Flow

enum class ChatRole { SYSTEM, USER, ASSISTANT }
data class ChatMessage(val role: ChatRole, val content: String)
enum class ModelLocation { LOCAL, CLOUD }
data class ModelInfo(val id: String, val displayName: String, val location: ModelLocation)

data class GenerationParameters(
    val maxTokens: Int = 256,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val seed: Int = 42,
    /**
     * Which conversation this prompt belongs to, for an engine that may still hold the prompt it
     * read last time (P2-1c). [null] — the default — means "reuse nothing": every round reads the
     * whole prompt exactly as it did before this field existed, and the engine's cache state is
     * neither read nor written. Only a [ChatModel] that owns such a cache may look at this value.
     */
    val cacheKey: String? = null,
    /**
     * P2-1d：这一轮该把检查点存在哪个 token 位置（用户这一轮说完、助手头之前）。
     *
     * `-1`（默认）= 不存，行为与之前完全一致。只有拿到 [cacheKey] 且引擎开着检查点开关时才有意义；
     * 它**不能超过本轮的公共前缀**，否则恢复出来的状态对不上位置。
     */
    val checkpointPos: Int = -1,
) {
    init {
        require(maxTokens in 1..8192) { "生成长度须为 1–8192" }
        require(temperature.isFinite() && temperature in 0f..2f) { "温度须为 0–2" }
        require(topP.isFinite() && topP > 0f && topP <= 1f) { "topP 须大于 0 且不超过 1" }
        require(cacheKey == null || cacheKey.isNotBlank()) { "缓存键不能是空白" }
        require(cacheKey == null || cacheKey.length <= 200) { "缓存键过长" }
        require(checkpointPos == -1 || checkpointPos >= 1) { "检查点位置要么 -1（不存）要么是正整数" }
    }
}

/**
 * Cold stream of ordered body-text deltas, never cumulative snapshots.
 * Collector cancellation must stop work, including prompt evaluation.
 * Failures propagate through the flow; hidden reasoning is excluded from body text.
 */
interface ChatModel {
    val info: ModelInfo
    fun generate(messages: List<ChatMessage>, parameters: GenerationParameters = GenerationParameters()): Flow<String>
    /** Thread-safe, nonblocking stop for the active operation, including loading. */
    fun stop()
}

