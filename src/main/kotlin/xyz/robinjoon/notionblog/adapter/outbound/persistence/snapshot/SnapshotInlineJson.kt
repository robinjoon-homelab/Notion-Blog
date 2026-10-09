package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.inline.TextAnnotations

internal fun toJsonInlineList(inline: List<InlineContent>): ArrayNode =
    arrayNode().also { values ->
        inline.forEach {
            values.add(toJsonInline(it))
        }
    }

private fun toJsonInline(inline: InlineContent): ObjectNode =
    objectNode().apply {
        set("annotations", toJson(inline.annotations))
        when (inline) {
            is InlineContent.Text -> {
                put("kind", "text")
                put("text", inline.text)
                set("link", nullableJson(inline.link, ::toJsonLink))
            }

            is InlineContent.Equation -> {
                put("kind", "equation")
                put("expression", inline.expression)
            }

            is InlineContent.Mention -> {
                put("kind", "mention")
                put("label", inline.label)
                put("mentionKind", inline.kind.logicalValue())
                set("target", nullableJson(inline.target, ::toJsonLink))
            }
        }
    }

internal fun fromJsonInline(node: JsonNode): InlineContent {
    val inline = node.requireObject("inline content")
    val annotations = fromJsonAnnotations(inline.requiredObject("annotations", "inline content"))
    return when (val kind = inline.requiredText("kind", "inline content")) {
        "text" -> {
            InlineContent.Text(inline.requiredText("text", kind), annotations, inline.optionalObject("link")?.let(::fromJsonLink))
        }

        "equation" -> {
            InlineContent.Equation(inline.requiredText("expression", kind), annotations)
        }

        "mention" -> {
            InlineContent.Mention(
                inline.requiredText("label", kind),
                inline.requiredEnum("mentionKind", kind, ::mentionKind),
                annotations,
                inline.optionalObject("target")?.let(::fromJsonLink),
            )
        }

        else -> {
            throw IllegalArgumentException("unsupported inline content kind: $kind")
        }
    }
}

private fun toJson(annotations: TextAnnotations): ObjectNode =
    objectNode().apply {
        put("bold", annotations.bold)
        put("italic", annotations.italic)
        put("strikethrough", annotations.strikethrough)
        put("underline", annotations.underline)
        put("code", annotations.code)
        set("foreground", nullableJson(annotations.foreground) { jsonString(it.logicalValue()) })
        set("background", nullableJson(annotations.background) { jsonString(it.logicalValue()) })
    }

private fun fromJsonAnnotations(annotations: ObjectNode): TextAnnotations =
    TextAnnotations(
        bold = annotations.requiredBoolean("bold", "annotations"),
        italic = annotations.requiredBoolean("italic", "annotations"),
        strikethrough = annotations.requiredBoolean("strikethrough", "annotations"),
        underline = annotations.requiredBoolean("underline", "annotations"),
        code = annotations.requiredBoolean("code", "annotations"),
        foreground = annotations.optionalEnum("foreground", ::colorToken),
        background = annotations.optionalEnum("background", ::colorToken),
    )

internal fun ObjectNode.requiredInlineList(
    name: String,
    context: String,
): List<InlineContent> = requiredArray(name, context).toList().map(::fromJsonInline)

internal fun ObjectNode.optionalInlineList(name: String): List<InlineContent> =
    get(name)
        ?.requireArray(name)
        ?.toList()
        ?.map(::fromJsonInline)
        .orEmpty()
