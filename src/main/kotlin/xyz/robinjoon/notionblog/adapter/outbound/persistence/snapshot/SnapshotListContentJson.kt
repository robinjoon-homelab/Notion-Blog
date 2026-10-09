package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.domain.post.block.content.ListBlockContent

internal val listBlockKinds = setOf("bulleted_list_item", "numbered_list_item", "to_do")

internal fun kindOfList(content: ListBlockContent): String =
    when (content) {
        is ListBlockContent.BulletedItem -> "bulleted_list_item"
        is ListBlockContent.NumberedItem -> "numbered_list_item"
        is ListBlockContent.ToDoItem -> "to_do"
    }

internal fun toJsonList(content: ListBlockContent): ObjectNode =
    when (content) {
        is ListBlockContent.BulletedItem -> encodeBulletedListItem(content)
        is ListBlockContent.NumberedItem -> encodeNumberedListItem(content)
        is ListBlockContent.ToDoItem -> encodeToDo(content)
    }

internal fun fromJsonList(
    kind: String,
    content: ObjectNode,
): ListBlockContent =
    when (kind) {
        "bulleted_list_item" -> ListBlockContent.BulletedItem(content.requiredInlineList("richText", kind))
        "numbered_list_item" -> decodeNumberedListItem(kind, content)
        "to_do" -> ListBlockContent.ToDoItem(content.requiredInlineList("richText", kind), content.requiredBoolean("checked", kind))
        else -> throw IllegalArgumentException("unsupported list block kind: $kind")
    }

private fun encodeBulletedListItem(content: ListBlockContent.BulletedItem): ObjectNode =
    objectNode().apply {
        set("richText", toJsonInlineList(content.richText))
    }

private fun encodeNumberedListItem(content: ListBlockContent.NumberedItem): ObjectNode =
    objectNode().apply {
        set("richText", toJsonInlineList(content.richText))
        put("startNumber", content.startNumber)
        put("displayFormat", content.displayFormat.logicalValue())
        put("startsNewList", content.startsNewList)
    }

private fun encodeToDo(content: ListBlockContent.ToDoItem): ObjectNode =
    objectNode().apply {
        set("richText", toJsonInlineList(content.richText))
        put("checked", content.checked)
    }

private fun decodeNumberedListItem(
    kind: String,
    content: ObjectNode,
): ListBlockContent.NumberedItem =
    ListBlockContent.NumberedItem(
        richText = content.requiredInlineList("richText", kind),
        startNumber = content.requiredInt("startNumber", kind),
        displayFormat = content.requiredEnum("displayFormat", kind, ::numberedListFormat),
        startsNewList = content.optionalBoolean("startsNewList") ?: false,
    )
