package com.xavierxia.mycelium.contract

import java.time.OffsetDateTime

/**
 * The checks `manifest.schema.json` (draft 2020-12) states for a v0 pack.
 * The schema file itself is still loaded and checked: its required fields, constants and
 * patterns are what this runs, so a contract change that adds a field fails here instead of
 * silently passing.
 */
fun validateManifestNode(node: JsonValue, schema: JsonValue): List<String> {
    val errors = mutableListOf<String>()
    checkObject(node, schema, "$", errors)
    return errors
}

private fun checkObject(node: JsonValue, schema: JsonValue, path: String, errors: MutableList<String>) {
    if (!node.isObject) {
        errors += "$path 不是对象"
        return
    }
    val required = schema["required"]?.elements()?.map { it.asText() }.orEmpty()
    required.filter { node[it] == null }.forEach { errors += "$path 缺少 $it" }
    val properties = schema["properties"]
    val additional = schema["additionalProperties"]?.asBooleanOrNull() ?: true
    if (!additional) {
        val known = properties?.fieldNames().orEmpty()
        (node.fieldNames() - known).forEach { errors += "$path 含有未定义字段 $it" }
    }
    node.fieldNames().forEach { name ->
        val property = properties?.get(name) ?: return@forEach
        checkValue(node[name]!!, property, "$path.$name", errors)
    }
}

private fun checkValue(node: JsonValue, schema: JsonValue, path: String, errors: MutableList<String>) {
    schema["const"]?.let { expected ->
        if (node.asText() != expected.asText()) errors += "$path 必须是 ${expected.asText()}"
    }
    schema["type"]?.let { type ->
        val allowed = if (type.isArray) type.elements().map { it.asText() } else listOf(type.asText())
        if (node.typeName() !in allowed) errors += "$path 类型是 ${node.typeName()}，要求 $allowed"
    }
    schema["pattern"]?.let { pattern ->
        if (node.asText().matches(Regex(pattern.asText())).not()) {
            errors += "$path 不符合 ${pattern.asText()}"
        }
    }
    schema["minLength"]?.let { minimum ->
        if (node.asText().length < minimum.asInt()) errors += "$path 短于 ${minimum.asInt()}"
    }
    schema["minimum"]?.let { minimum ->
        if (node.isObject.not() && node.asInt() < minimum.asInt()) errors += "$path 小于 ${minimum.asInt()}"
    }
    schema["format"]?.let { format ->
        if (format.asText() == "date-time") {
            try {
                OffsetDateTime.parse(node.asText())
            } catch (_: Exception) {
                errors += "$path 不是 RFC 3339 时间"
            }
        }
    }
    if (schema["type"]?.asText() == "object" || schema["properties"] != null) {
        checkObject(node, schema, path, errors)
    }
}
