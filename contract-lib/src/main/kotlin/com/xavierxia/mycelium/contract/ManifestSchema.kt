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
    val required = schema["required"]?.elements()?.mapNotNull { it.asTextOrNull() }.orEmpty()
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
        if (!sameJson(node, expected)) errors += "$path 必须是 ${expected.asTextOrNull() ?: expected.typeName()}"
    }
    val typeMatches = schema["type"]?.let { type ->
        val allowed = if (type.isArray) type.elements().map { it.asTextOrNull().orEmpty() } else listOf(type.asTextOrNull().orEmpty())
        val matches = node.typeName() in allowed
        if (!matches) errors += "$path 类型是 ${node.typeName()}，要求 $allowed"
        matches
    } ?: true
    // A wrong type is only a validation error. Do not read it as a string or integer.
    if (!typeMatches) return
    schema["pattern"]?.asTextOrNull()?.let { pattern ->
        val text = node.asTextOrNull() ?: return@let
        if (!text.matches(Regex(pattern))) errors += "$path 不符合 $pattern"
    }
    schema["minLength"]?.asIntOrNull()?.let { minimum ->
        val text = node.asTextOrNull() ?: return@let
        if (text.length < minimum) errors += "$path 短于 $minimum"
    }
    schema["minimum"]?.asIntOrNull()?.let { minimum ->
        val actual = node.asIntOrNull() ?: return@let
        if (actual < minimum) errors += "$path 小于 $minimum"
    }
    schema["format"]?.asTextOrNull()?.let { format ->
        if (format == "date-time") {
            val text = node.asTextOrNull()
            if (text == null || runCatching { OffsetDateTime.parse(text) }.isFailure) {
                errors += "$path 不是 RFC 3339 时间"
            }
        }
    }
    if (schema["type"]?.asTextOrNull() == "object" || schema["properties"] != null) {
        checkObject(node, schema, path, errors)
    }
}

/** JSON Schema `const` compares type and value. `"0.1"` and `0.1` are not the same. */
private fun sameJson(actual: JsonValue, expected: JsonValue): Boolean {
    if (actual.typeName() != expected.typeName()) return false
    return when (actual.typeName()) {
        "null" -> true
        "string" -> actual.asTextOrNull() == expected.asTextOrNull()
        "boolean" -> actual.asBooleanOrNull() == expected.asBooleanOrNull()
        "integer", "number" -> actual.asNumberOrNull() == expected.asNumberOrNull()
        else -> false
    }
}
