package com.xavierxia.mycelium.model
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.*

class ChatModelTest {
    @Test fun orderedDeltasAndCollectorCancellation() = runTest {
        var released = false
        val model = object : ChatModel {
            override val info = ModelInfo("fake", "Fake", ModelLocation.LOCAL)
            override fun stop() = Unit
            override fun generate(messages: List<ChatMessage>, parameters: GenerationParameters): Flow<String> = flow {
                try { emit("你"); emit("好"); awaitCancellation() } finally { released = true }
            }
        }
        assertEquals(listOf("你", "好"), model.generate(listOf(ChatMessage(ChatRole.USER, "hi"))).take(2).toList())
        assertTrue(released)
    }
    @Test fun invalidParametersAreRejected() {
        assertFailsWith<IllegalArgumentException> { GenerationParameters(maxTokens = 0) }
        assertFailsWith<IllegalArgumentException> { GenerationParameters(temperature = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { GenerationParameters(topP = 0f) }
    }
}

