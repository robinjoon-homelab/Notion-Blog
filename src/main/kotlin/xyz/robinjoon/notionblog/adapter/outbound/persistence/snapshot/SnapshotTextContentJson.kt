package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent

internal val textBlockKinds = setOf("paragraph", "heading", "quote", "toggle", "callout", "code", "equation")

internal fun kindOfText(content: TextBlockContent): String =
    when (content) {
        is TextBlockContent.Paragraph -> "paragraph"
        is TextBlockContent.Heading -> "heading"
        is TextBlockContent.Quote -> "quote"
        is TextBlockContent.Toggle -> "toggle"
        is TextBlockContent.Callout -> "callout"
        is TextBlockContent.Code -> "code"
        is TextBlockContent.Equation -> "equation"
    }

internal fun toJsonText(content: TextBlockContent): ObjectNode =
    when (content) {
        is TextBlockContent.Paragraph -> encodeParagraph(content)
        is TextBlockContent.Heading -> encodeHeading(content)
        is TextBlockContent.Quote -> encodeQuote(content)
        is TextBlockContent.Toggle -> encodeToggle(content)
        is TextBlockContent.Callout -> encodeCallout(content)
        is TextBlockContent.Code -> encodeCode(content)
        is TextBlockContent.Equation -> encodeEquation(content)
    }

internal fun fromJsonText(
    kind: String,
    content: ObjectNode,
): TextBlockContent =
    when (kind) {
        "paragraph" -> {
            TextBlockContent.Paragraph(content.requiredInlineList("richText", kind))
        }

        "heading" -> {
            decodeHeading(kind, content)
        }

        "quote" -> {
            TextBlockContent.Quote(content.requiredInlineList("richText", kind))
        }

        "toggle" -> {
            TextBlockContent.Toggle(content.requiredInlineList("richText", kind))
        }

        "callout" -> {
            TextBlockContent.Callout(
                content.requiredInlineList("richText", kind),
                content.optionalObject("icon")?.let(::fromJsonIcon),
            )
        }

        "code" -> {
            decodeCode(kind, content)
        }

        "equation" -> {
            TextBlockContent.Equation(content.requiredText("expression", kind))
        }

        else -> {
            throw IllegalArgumentException("unsupported text block kind: $kind")
        }
    }

private fun encodeParagraph(content: TextBlockContent.Paragraph): ObjectNode =
    objectNode().apply {
        set("richText", toJsonInlineList(content.richText))
    }

private fun encodeHeading(content: TextBlockContent.Heading): ObjectNode =
    objectNode().apply {
        put("level", content.level.logicalValue())
        set("richText", toJsonInlineList(content.richText))
        put("isToggleable", content.isToggleable)
    }

private fun encodeQuote(content: TextBlockContent.Quote): ObjectNode =
    objectNode().apply {
        set("richText", toJsonInlineList(content.richText))
    }

private fun encodeToggle(content: TextBlockContent.Toggle): ObjectNode =
    objectNode().apply {
        set("richText", toJsonInlineList(content.richText))
    }

private fun encodeCallout(content: TextBlockContent.Callout): ObjectNode =
    objectNode().apply {
        set("richText", toJsonInlineList(content.richText))
        set("icon", nullableJson(content.icon, ::toJsonIcon))
    }

private fun encodeCode(content: TextBlockContent.Code): ObjectNode =
    objectNode().apply {
        set("richText", toJsonInlineList(content.richText))
        put("language", content.language)
        set("caption", toJsonInlineList(content.caption))
    }

private fun encodeEquation(content: TextBlockContent.Equation): ObjectNode =
    objectNode().apply {
        put("expression", content.expression)
    }

private fun decodeHeading(
    kind: String,
    content: ObjectNode,
): TextBlockContent.Heading =
    TextBlockContent.Heading(
        content.requiredEnum("level", kind, ::headingLevel),
        content.requiredInlineList("richText", kind),
        content.requiredBoolean("isToggleable", kind),
    )

private fun decodeCode(
    kind: String,
    content: ObjectNode,
): TextBlockContent.Code =
    TextBlockContent.Code(
        content.requiredInlineList("richText", kind),
        content.requiredText("language", kind),
        content.requiredInlineList("caption", kind),
    )
