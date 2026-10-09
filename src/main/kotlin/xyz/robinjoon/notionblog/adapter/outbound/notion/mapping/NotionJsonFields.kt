package xyz.robinjoon.notionblog.adapter.outbound.notion.mapping

import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale

internal fun JsonNode.safeUri(field: String): URI = parseSafeUri(requiredText(field))

internal fun parseSafeUri(value: String): URI {
    val uri =
        try {
            URI(value)
        } catch (exception: URISyntaxException) {
            throw NotionBlockMappingException("URL is invalid", exception)
        }
    if (!uri.isAbsolute || uri.scheme.lowercase(Locale.ROOT) !in setOf("http", "https")) {
        throw NotionBlockMappingException("URL must use http or https")
    }
    return uri
}

internal fun parseInstant(value: String): Instant =
    try {
        Instant.parse(value)
    } catch (exception: DateTimeParseException) {
        throw NotionBlockMappingException("media expiry is invalid", exception)
    }

internal fun JsonNode.requiredText(field: String): String =
    optionalText(field)
        ?: throw NotionBlockMappingException("required $field is missing")

internal fun JsonNode.optionalText(field: String): String? =
    get(field)
        ?.takeIf(JsonNode::isString)
        ?.stringValue()
        ?.takeIf(String::isNotBlank)

internal fun JsonNode.requiredBoolean(field: String): Boolean =
    optionalBoolean(field)
        ?: throw NotionBlockMappingException("required $field is missing")

internal fun JsonNode.optionalBoolean(field: String): Boolean? =
    get(field)
        ?.takeIf(JsonNode::isBoolean)
        ?.asBoolean()

internal fun JsonNode.requiredPositiveInt(field: String): Int =
    get(field)
        ?.takeIf(JsonNode::isInt)
        ?.intValue()
        ?.takeIf { it > 0 }
        ?: throw NotionBlockMappingException("$field must be a positive integer")

internal fun JsonNode.optionalDouble(field: String): Double? =
    get(field)
        ?.takeIf(JsonNode::isNumber)
        ?.doubleValue()

internal fun JsonNode.requiredObject(field: String): JsonNode =
    get(field)
        ?.takeIf(JsonNode::isObject)
        ?: throw NotionBlockMappingException("required $field object is missing")

internal fun JsonNode.optionalObject(field: String): JsonNode? = get(field)?.takeIf(JsonNode::isObject)

internal fun JsonNode.requiredArray(field: String): List<JsonNode> =
    get(field)
        ?.takeIf(JsonNode::isArray)
        ?.toList()
        ?: throw NotionBlockMappingException("required $field array is missing")

internal fun JsonNode.optionalArray(field: String): List<JsonNode>? =
    get(field)
        ?.takeIf(JsonNode::isArray)
        ?.toList()
