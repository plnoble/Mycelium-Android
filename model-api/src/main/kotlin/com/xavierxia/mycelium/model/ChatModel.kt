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
) {
    init {
        require(maxTokens in 1..8192) { "生成长度须为 1–8192" }
        require(temperature.isFinite() && temperature in 0f..2f) { "温度须为 0–2" }
        require(topP.isFinite() && topP > 0f && topP <= 1f) { "topP 须大于 0 且不超过 1" }
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

