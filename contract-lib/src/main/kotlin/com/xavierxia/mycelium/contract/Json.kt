package com.xavierxia.mycelium.contract

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Strict JSON. Trailing commas and other repairs are rejected on purpose (VALIDATION.md §1.3). */
fun parseJson(text: String): JsonValue = JsonValue(JSONTokener(text).nextValue())

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

    fun asText(): String = when (raw) {
        is String -> raw
        null, JSONObject.NULL -> ""
        else -> raw.toString()
    }

    fun asInt(): Int = when (raw) {
        is Number -> raw.toInt()
        else -> error("不是整数：$raw")
    }

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
