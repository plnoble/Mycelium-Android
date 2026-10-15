package com.xavierxia.mycelium.contract

/**
 * serde_json string encoding, required by persona-pack SPEC §3 so both ends hash the same bytes.
 * Short escapes for the usual controls; every other U+0000..U+001F becomes `\u00xx`.
 */
fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code in 0x00..0x1F) {
                append("\\u")
                append(ch.code.toString(16).padStart(4, '0'))
            } else {
                append(ch)
            }
        }
    }
    append('"')
}

/** One canonical JSON Lines row: every SQL integer is a decimal string, NULL is JSON null. */
fun jsonRow(cells: List<String?>): String =
    cells.joinToString(prefix = "[", postfix = "]", separator = ",") { cell ->
        if (cell == null) "null" else jsonString(cell)
    }
