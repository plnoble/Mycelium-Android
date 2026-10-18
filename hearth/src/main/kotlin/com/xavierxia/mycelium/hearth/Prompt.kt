package com.xavierxia.mycelium.hearth

import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.ChatRole
import com.xavierxia.mycelium.model.GenerationParameters

data class ContextWindow(val messages: List<ChatMessage>, val droppedTurns: Int)

/**
 * The character sheet is always the system message, and the current question is always the last
 * message. Empty fields are omitted. When the window is too small, the oldest complete turns are
 * dropped first; the sheet and the question stay.
 */
fun assemblePrompt(character: HearthCharacter, history: List<ChatMessage>, question: String, contextTokens: Int): ContextWindow {
    require(contextTokens >= 64) { "上下文长度过短" }
    val system = ChatMessage(ChatRole.SYSTEM, systemPrompt(character))
    val systemTokens = estimateTokens(system.content)
    require(systemTokens + 32 < contextTokens) { "人物设定比上下文还长，请缩短设定或加大上下文" }
    val turns = completeTurns(history)
    val questionTokens = estimateTokens(question)
    var first = 0
    while (first < turns.size && systemTokens + turns.subList(first, turns.size).sumOf { estimateTokens(it.content) } + questionTokens + 32 > contextTokens) {
        first += 2
    }
    val kept = if (first >= turns.size) emptyList() else turns.subList(first, turns.size)
    return ContextWindow(listOf(system) + kept + ChatMessage(ChatRole.USER, question), (turns.size - kept.size) / 2)
}

/** The generation parameters this character was set up with. */
fun HearthCharacter.parameters() = GenerationParameters(maxTokens = maxTokens, temperature = temperature)

fun systemPrompt(character: HearthCharacter): String = buildString {
    append("你是「").append(character.name.trim()).append("」。用这个身份说话，不要说自己是程序或模型。")
    line("性格", character.personality)
    line("说话方式", character.speech)
    line("背景", character.background)
    line("和对方的关系", character.relationship)
    if (character.greeting.isNotBlank()) append("\n你的开场白是：").append(character.greeting.trim())
}.trim()

/** A rough character budget: CJK counts as one token, other letters group by about four. */
fun estimateTokens(text: String): Int {
    var tokens = 0
    var latin = 0
    text.forEach { char ->
        if (char.code in 0x4E00..0x9FFF || char.code > 0xFF) {
            tokens += latin / 4 + 1
            latin = 0
        } else if (!char.isWhitespace()) {
            latin += 1
        } else if (latin > 0) {
            tokens += (latin + 3) / 4
            latin = 0
        }
    }
    return tokens + (latin + 3) / 4
}

private fun StringBuilder.line(label: String, value: String) {
    val trimmed = value.trim()
    if (trimmed.isNotEmpty()) append("\n").append(label).append("：").append(trimmed)
}

/** Stored history as complete turns: an unpaired trailing question is not a finished turn. */
private fun completeTurns(history: List<ChatMessage>): List<ChatMessage> {
    val body = history.filter { it.role != ChatRole.SYSTEM && it.content.isNotBlank() }
    val finished = if (body.isNotEmpty() && body.last().role == ChatRole.USER) body.dropLast(1) else body
    return if (finished.size % 2 == 1) finished.drop(1) else finished
}