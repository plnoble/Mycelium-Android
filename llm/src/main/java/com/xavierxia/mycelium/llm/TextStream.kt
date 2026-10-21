package com.xavierxia.mycelium.llm

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Handles a token boundary in the middle of a UTF-8 code point. */
internal class Utf8Stream {
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = byteArrayOf()
    fun push(bytes: ByteArray, last: Boolean = false): String {
        val input = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate(input.remaining() + 4)
        decoder.decode(input, output, last)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        if (last) decoder.flush(output)
        output.flip()
        return output.toString()
    }
}

/** Streaming tag filter: never exposes partial tags or unterminated reasoning. */
internal class ThinkingFilter(private var hidden: Boolean = false) {
    private var pending = ""

    /**
 * Whether the next pushed text starts inside a hidden span (P2-1c, step E). The engine reads this
 * *before* pushing a native token to attribute that token.
 */
    val isHidden: Boolean get() = hidden

    /**
     * Whether **any** part of the text pushed last was inside a hidden span (P2-1c, step E). This is
     * the attribution the engine records: a token that opens the span, one that closes it, and a
     * token that carries a whole hidden segment between the tags all count, because at least part of
     * their text was hidden. Set by every [push]; no generation is changed by it.
     */
    var sawHiddenText: Boolean = false
        private set

    fun push(text: String): String {
        sawHiddenText = hidden
        pending += text
        val output = StringBuilder()
        while (pending.isNotEmpty()) {
            // 这一轮循环消耗/保留的文本只要处在隐藏状态里，这个 token 就算隐藏（E 的口径）。
            sawHiddenText = sawHiddenText || hidden
            val marker = if (hidden) "</think>" else "<think>"
            val index = pending.indexOf(marker)
            if (index >= 0) {
                if (!hidden) output.append(pending.substring(0, index))
                pending = pending.substring(index + marker.length)
                hidden = !hidden
            } else {
                val retained = (1 until marker.length).lastOrNull { pending.endsWith(marker.take(it)) } ?: 0
                if (!hidden) output.append(pending.dropLast(retained))
                pending = pending.takeLast(retained)
                break
            }
        }
        return output.toString()
    }
    // Partial marker at EOS/cancellation is deliberately discarded.
}

