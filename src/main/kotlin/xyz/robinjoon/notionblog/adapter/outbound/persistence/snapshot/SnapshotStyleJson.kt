package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.domain.post.block.style.BlockStyle
import xyz.robinjoon.notionblog.domain.post.block.style.StyleVariant
import xyz.robinjoon.notionblog.domain.post.block.style.WidthToken

internal fun toJson(style: BlockStyle): ObjectNode =
    objectNode().apply {
        set("foreground", nullableJson(style.foreground) { jsonString(it.logicalValue()) })
        set("background", nullableJson(style.background) { jsonString(it.logicalValue()) })
        set("alignment", nullableJson(style.alignment) { jsonString(it.logicalValue()) })
        set("width", nullableJson(style.width) { numberNode(it.ratio) })
        set("variant", nullableJson(style.variant) { jsonString(it.value) })
    }

internal fun fromJsonStyle(style: ObjectNode): BlockStyle =
    BlockStyle(
        foreground = style.optionalEnum("foreground", ::colorToken),
        background = style.optionalEnum("background", ::colorToken),
        alignment = style.optionalEnum("alignment", ::alignment),
        width = style.optionalDouble("width")?.let(::WidthToken),
        variant = style.optionalText("variant")?.let(::StyleVariant),
    )
