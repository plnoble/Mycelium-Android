package com.xavierxia.mycelium.contract

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * One JSON value, then only whitespace. org.json still accepts a trailing comma
 * (the desktop reference repairs that); that is not a contract behavior (VALIDATION.md §1.3).
 * Unquoted values and single quotes are not JSON and are rejected.
 */
fun parseJson(text: String): JsonValue {
    requireStrictJson(text)
    val tokener = JSONTokener(text)
    val value = JsonValue(tokener.nextValue())
    while (tokener.more()) {
        val next = tokener.next()
        if (next != ' ' && next != '\n' && next != '\r' && next != '\t') {
            throw IllegalArgumentException("JSON 值后面还有内容")
        }
    }
    return value
}

class JsonValue(private val raw: Any?) {
    val isNull: Boolean get() = raw == null || raw == JSONObject.NULL
    val isArray: Boolean get() = raw is JSONArray
    val isObject: Boolean get() = raw is JSONObject

    operator fun get(name: String): JsonValue? {
        val obj = raw as? JSONObject ?: return null
        if (!obj.has(name)) return null
        // A present JSON null is a value (schema allows it); a missing field is Kotlin null.
        return JsonValue(if (obj.isNull(name)) null else obj.get(name))
    }

    fun asTextOrNull(): String? = raw as? String

    fun stringElementsOrNull(): List<String>? {
        if (raw !is JSONArray) return null
        return List(raw.length()) { index -> raw.get(index) as? String ?: return null }
    }

    fun asInt(): Int = asIntOrNull() ?: error("不是整数：$raw")

    fun asIntOrNull(): Int? = when (raw) {
        is Int, is Long -> (raw as Number).toInt()
        else -> null
    }

    fun asNumberOrNull(): Double? = (raw as? Number)?.toDouble()

    fun asBoolean(): Boolean = when (raw) {
        is Boolean -> raw
        else -> error("不是布尔：$raw")
    }

    fun size(): Int = (raw as JSONArray).length()

    fun isEmpty(): Boolean = size() == 0

    fun elements(): List<JsonValue> = List(size()) { index -> JsonValue((raw as JSONArray).get(index)) }

    fun fieldNames(): Set<String> = (raw as JSONObject).keys().asSequence().toSet()

    /** Schema keywords such as `additionalProperties` are either a boolean or a nested schema. */
    fun asBooleanOrNull(): Boolean? = raw as? Boolean

    fun typeName(): String = when (raw) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> "object"
        is JSONArray -> "array"
        is Boolean -> "boolean"
        is Int, is Long -> "integer"
        is Number -> "number"
        else -> "string"
    }
}

/**
 * org.json accepts single quotes and unquoted keys. Those are not JSON.
 * A trailing comma is still accepted here; the desktop side repairs it, and that is not a contract.
 */
private fun requireStrictJson(text: String) {
    var index = 0
    var inString = false
    var escaped = false
    while (index < text.length) {
        val ch = text[index]
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
                ch.code < 0x20 -> throw IllegalArgumentException("字符串里有未转义控制字符")
            }
            index++
            continue
        }
        index = when (ch) {
            '"' -> {
                inString = true
                index + 1
            }
            '{', '}', '[', ']', ':', ',', ' ', '\n', '\r', '\t' -> index + 1
            '-', in '0'..'9' -> consumeJsonNumber(text, index)
            't' -> consumeLiteral(text, index, "true")
            'f' -> consumeLiteral(text, index, "false")
            'n' -> consumeLiteral(text, index, "null")
            else -> throw IllegalArgumentException("不是合法 JSON")
        }
    }
    if (inString) throw IllegalArgumentException("不是合法 JSON")
}

private fun consumeLiteral(text: String, start: Int, literal: String): Int {
    if (!text.startsWith(literal, start)) throw IllegalArgumentException("不是合法 JSON")
    return start + literal.length
}

private fun consumeJsonNumber(text: String, start: Int): Int {
    var index = start
    if (text[index] == '-') index++
    if (index >= text.length || !text[index].isDigit()) throw IllegalArgumentException("不是合法 JSON")
    if (text[index] == '0') {
        index++
    } else {
        while (index < text.length && text[index].isDigit()) index++
    }
    if (index < text.length && text[index] == '.') {
        index++
        val fraction = index
        while (index < text.length && text[index].isDigit()) index++
        if (index == fraction) throw IllegalArgumentException("不是合法 JSON")
    }
    if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
        index++
        if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
        val exponent = index
        while (index < text.length && text[index].isDigit()) index++
        if (index == exponent) throw IllegalArgumentException("不是合法 JSON")
    }
    return index
}
