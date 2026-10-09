package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId

internal fun toJsonLink(link: LinkTarget): ObjectNode =
    objectNode().apply {
        when (link) {
            is LinkTarget.ExternalUrl -> {
                put("kind", "external_url")
                put("url", link.url.toString())
            }

            is LinkTarget.SourceDocument -> {
                put("kind", "source_document")
                set("reference", toJsonReference(link.reference))
                set("originalUrl", nullableJson(link.originalUrl) { jsonString(it.toString()) })
            }
        }
    }

internal fun fromJsonLink(node: JsonNode): LinkTarget {
    val link = node.requireObject("link")
    return when (val kind = link.requiredText("kind", "link")) {
        "external_url" -> {
            LinkTarget.ExternalUrl(link.requiredUri("url", kind))
        }

        "source_document" -> {
            LinkTarget.SourceDocument(
                fromJsonReference(link.requiredObject("reference", kind)),
                link.optionalUri("originalUrl"),
            )
        }

        else -> {
            throw IllegalArgumentException("unsupported link kind: $kind")
        }
    }
}

internal fun toJsonReference(reference: SourceDocumentRef): ObjectNode =
    objectNode().apply {
        put("sourceId", reference.sourceId.value)
        put("externalId", reference.externalId)
    }

internal fun fromJsonReference(reference: ObjectNode): SourceDocumentRef =
    SourceDocumentRef(
        SourceId(reference.requiredText("sourceId", "source document reference")),
        reference.requiredText("externalId", "source document reference"),
    )
