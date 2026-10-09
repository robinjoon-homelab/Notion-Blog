package xyz.robinjoon.notionblog.adapter.outbound.notion.client

import tools.jackson.databind.JsonNode

internal fun JsonNode.requiredText(field: String): String =
    optionalText(field)
        ?: throw IllegalArgumentException("Missing required field")

internal fun JsonNode.optionalText(field: String): String? =
    get(field)
        ?.takeUnless(JsonNode::isNull)
        ?.takeIf(JsonNode::isString)
        ?.stringValue()
        ?.takeIf(String::isNotBlank)

internal fun JsonNode.requiredNullableText(field: String): String? {
    val value = get(field) ?: throw IllegalArgumentException("Missing required field")
    if (value.isNull) return null
    require(value.isString && value.stringValue().isNotBlank())
    return value.stringValue()
}

internal fun JsonNode.nullableText(field: String): String? {
    val value = get(field)?.takeUnless(JsonNode::isNull) ?: return null
    require(value.isString)
    return value.stringValue()
}

internal fun JsonNode.optionalCursor(): String? {
    val cursor = get("next_cursor") ?: return null
    if (cursor.isNull) {
        return null
    }
    return cursor.takeIf(JsonNode::isString)?.stringValue()
        ?: throw IllegalArgumentException("Invalid pagination cursor")
}

internal fun JsonNode.requiredBoolean(field: String): Boolean =
    get(field)
        ?.takeIf(JsonNode::isBoolean)
        ?.asBoolean()
        ?: throw IllegalArgumentException("Missing required field")

internal fun JsonNode.nullableBoolean(field: String): Boolean? {
    val value = get(field)?.takeUnless(JsonNode::isNull) ?: return null
    require(value.isBoolean)
    return value.asBoolean()
}

internal fun JsonNode.nullableNonnegativeInt(field: String): Int? {
    val value = get(field)?.takeUnless(JsonNode::isNull) ?: return null
    require(value.isInt && value.intValue() >= 0)
    return value.intValue()
}

internal fun JsonNode.nullableObject(field: String): JsonNode? {
    val value = get(field)?.takeUnless(JsonNode::isNull) ?: return null
    require(value.isObject)
    return value
}

internal fun JsonNode.requiredObject(field: String): JsonNode =
    get(field)
        ?.takeIf(JsonNode::isObject)
        ?: throw IllegalArgumentException("Missing required object")

internal fun JsonNode.requiredArray(field: String): JsonNode =
    get(field)
        ?.takeIf(JsonNode::isArray)
        ?: throw IllegalArgumentException("Missing required array")
