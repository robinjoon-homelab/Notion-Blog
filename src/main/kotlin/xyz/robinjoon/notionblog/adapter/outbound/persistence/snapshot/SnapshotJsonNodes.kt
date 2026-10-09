package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

internal fun objectNode(): ObjectNode = JsonNodeFactory.instance.objectNode()

internal fun arrayNode(): ArrayNode = JsonNodeFactory.instance.arrayNode()

internal fun nullNode(): JsonNode = JsonNodeFactory.instance.nullNode()

internal fun jsonString(value: String): JsonNode = JsonNodeFactory.instance.stringNode(value)

internal fun numberNode(value: Double): JsonNode = JsonNodeFactory.instance.numberNode(value)

internal fun <T> nullableJson(
    value: T?,
    encode: (T) -> JsonNode,
): JsonNode = if (value == null) nullNode() else encode(value)

internal fun <T> jsonArray(
    values: List<T>,
    encode: (T) -> JsonNode,
): ArrayNode = arrayNode().addAll(values.map(encode))
