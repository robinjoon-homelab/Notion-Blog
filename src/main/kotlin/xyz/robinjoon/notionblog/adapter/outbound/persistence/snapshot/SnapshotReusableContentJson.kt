package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.domain.post.block.content.ReusableBlockContent

internal val reusableBlockKinds = setOf("synchronized", "template")

internal fun kindOfReusable(content: ReusableBlockContent): String =
    when (content) {
        is ReusableBlockContent.Synchronized -> "synchronized"
        is ReusableBlockContent.Template -> "template"
    }

internal fun toJsonReusable(content: ReusableBlockContent): ObjectNode =
    when (content) {
        is ReusableBlockContent.Synchronized -> encodeSynchronized(content)
        is ReusableBlockContent.Template -> encodeTemplate(content)
    }

internal fun fromJsonReusable(
    kind: String,
    content: ObjectNode,
): ReusableBlockContent =
    when (kind) {
        "synchronized" -> ReusableBlockContent.Synchronized(content.optionalObject("origin")?.let(::fromJsonOrigin))
        "template" -> ReusableBlockContent.Template(content.optionalInlineList("title"))
        else -> throw IllegalArgumentException("unsupported reusable block kind: $kind")
    }

private fun encodeSynchronized(content: ReusableBlockContent.Synchronized): ObjectNode =
    objectNode().apply {
        set("origin", nullableJson(content.origin, ::toJsonOrigin))
    }

private fun encodeTemplate(content: ReusableBlockContent.Template): ObjectNode =
    objectNode().apply {
        set("title", toJsonInlineList(content.title))
    }
