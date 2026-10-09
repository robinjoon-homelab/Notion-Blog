package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.time.format.DateTimeParseException

internal fun JsonNode.requireObject(context: String): ObjectNode =
    this as? ObjectNode
        ?: throw IllegalArgumentException("$context must be an object")

internal fun ObjectNode.requiredObject(
    name: String,
    context: String,
): ObjectNode = requiredNode(name, context).requireObject("$context.$name")

internal fun ObjectNode.optionalObject(name: String): ObjectNode? = get(name)?.takeUnless(JsonNode::isNull)?.requireObject(name)

internal fun ObjectNode.requiredArray(
    name: String,
    context: String,
): ArrayNode =
    requiredNode(name, context) as? ArrayNode
        ?: throw IllegalArgumentException("$context.$name must be an array")

internal fun JsonNode.requireArray(context: String): ArrayNode =
    this as? ArrayNode
        ?: throw IllegalArgumentException("$context must be an array")

internal fun ObjectNode.requiredText(
    name: String,
    context: String,
): String {
    val value = requiredNode(name, context)
    require(value.isString) { "$context.$name must be a string" }
    return value.asString()
}

internal fun ObjectNode.optionalText(name: String): String? =
    get(name)?.takeUnless(JsonNode::isNull)?.let { value ->
        require(value.isString) { "$name must be a string" }
        value.asString()
    }

internal fun ObjectNode.requiredBoolean(
    name: String,
    context: String,
): Boolean {
    val value = requiredNode(name, context)
    require(value.isBoolean) { "$context.$name must be a boolean" }
    return value.asBoolean()
}

internal fun ObjectNode.optionalBoolean(name: String): Boolean? {
    val value = get(name)?.takeUnless(JsonNode::isNull) ?: return null
    require(value.isBoolean) { "$name must be a boolean" }
    return value.asBoolean()
}

internal fun ObjectNode.requiredInt(
    name: String,
    context: String,
): Int {
    val value = requiredNode(name, context)
    require(value.isIntegralNumber && value.canConvertToInt()) { "$context.$name must be an integer" }
    return value.asInt()
}

internal fun ObjectNode.optionalInt(name: String): Int? =
    get(name)?.takeUnless(JsonNode::isNull)?.let { value ->
        require(value.isIntegralNumber && value.canConvertToInt()) { "$name must be an integer" }
        value.asInt()
    }

internal fun ObjectNode.optionalDouble(name: String): Double? =
    get(name)?.takeUnless(JsonNode::isNull)?.let { value ->
        require(value.isNumber) { "$name must be a number" }
        value.asDouble()
    }

internal fun ObjectNode.requiredUri(
    name: String,
    context: String,
): URI = uri(requiredText(name, context), "$context.$name")

internal fun ObjectNode.optionalUri(name: String): URI? = optionalText(name)?.let { uri(it, name) }

internal fun ObjectNode.optionalInstant(name: String): Instant? =
    optionalText(name)?.let {
        try {
            Instant.parse(it)
        } catch (exception: DateTimeParseException) {
            throw IllegalArgumentException("$name must be an instant", exception)
        }
    }

internal fun <T> ObjectNode.requiredEnum(
    name: String,
    context: String,
    parser: (String) -> T,
): T = parser(requiredText(name, context))

internal fun <T> ObjectNode.optionalEnum(
    name: String,
    parser: (String) -> T,
): T? = optionalText(name)?.let(parser)

private fun ObjectNode.requiredNode(
    name: String,
    context: String,
): JsonNode =
    get(name)
        ?: throw IllegalArgumentException("$context.$name is required")

private fun uri(
    value: String,
    context: String,
): URI =
    try {
        URI(value)
    } catch (exception: URISyntaxException) {
        throw IllegalArgumentException("$context must be a URI", exception)
    }
