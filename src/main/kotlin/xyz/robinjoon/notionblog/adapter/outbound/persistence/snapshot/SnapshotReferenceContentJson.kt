package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent

internal val referenceBlockKinds = setOf("child_post", "document_link", "database_link", "breadcrumb", "table_of_contents")

internal fun kindOfReference(content: ReferenceBlockContent): String =
    when (content) {
        is ReferenceBlockContent.ChildPost -> "child_post"
        is ReferenceBlockContent.DocumentLink -> "document_link"
        is ReferenceBlockContent.DatabaseLink -> "database_link"
        is ReferenceBlockContent.Breadcrumb -> "breadcrumb"
        ReferenceBlockContent.TableOfContents -> "table_of_contents"
    }

internal fun toJsonReference(content: ReferenceBlockContent): ObjectNode =
    when (content) {
        is ReferenceBlockContent.ChildPost -> encodeChildPost(content)
        is ReferenceBlockContent.DocumentLink -> encodeDocumentLink(content)
        is ReferenceBlockContent.DatabaseLink -> encodeDatabaseLink(content)
        is ReferenceBlockContent.Breadcrumb -> encodeBreadcrumb(content)
        ReferenceBlockContent.TableOfContents -> objectNode()
    }

internal fun fromJsonReference(
    kind: String,
    content: ObjectNode,
): ReferenceBlockContent =
    when (kind) {
        "child_post" -> decodeChildPost(kind, content)
        "document_link" -> decodeDocumentLink(kind, content)
        "database_link" -> decodeDatabaseLink(kind, content)
        "breadcrumb" -> ReferenceBlockContent.Breadcrumb(content.requiredArray("items", kind).toList().map(::fromJsonLink))
        "table_of_contents" -> ReferenceBlockContent.TableOfContents
        else -> throw IllegalArgumentException("unsupported reference block kind: $kind")
    }

private fun encodeChildPost(content: ReferenceBlockContent.ChildPost): ObjectNode =
    objectNode().apply {
        put("title", content.title)
        set("reference", toJsonReference(content.reference))
    }

private fun encodeDocumentLink(content: ReferenceBlockContent.DocumentLink): ObjectNode =
    objectNode().apply {
        set("reference", toJsonReference(content.reference))
        set("originalUrl", nullableJson(content.originalUrl) { jsonString(it.toString()) })
    }

private fun encodeDatabaseLink(content: ReferenceBlockContent.DatabaseLink): ObjectNode =
    objectNode().apply {
        set("reference", toJsonReference(content.reference))
        set("originalUrl", nullableJson(content.originalUrl) { jsonString(it.toString()) })
        set("title", nullableJson(content.title, ::jsonString))
    }

private fun encodeBreadcrumb(content: ReferenceBlockContent.Breadcrumb): ObjectNode =
    objectNode().apply {
        set("items", jsonArray(content.items, ::toJsonLink))
    }

private fun decodeChildPost(
    kind: String,
    content: ObjectNode,
): ReferenceBlockContent.ChildPost =
    ReferenceBlockContent.ChildPost(
        content.requiredText("title", kind),
        fromJsonReference(content.requiredObject("reference", kind)),
    )

private fun decodeDocumentLink(
    kind: String,
    content: ObjectNode,
): ReferenceBlockContent.DocumentLink =
    ReferenceBlockContent.DocumentLink(
        fromJsonReference(content.requiredObject("reference", kind)),
        content.optionalUri("originalUrl"),
    )

private fun decodeDatabaseLink(
    kind: String,
    content: ObjectNode,
): ReferenceBlockContent.DatabaseLink =
    ReferenceBlockContent.DatabaseLink(
        reference = fromJsonReference(content.requiredObject("reference", kind)),
        originalUrl = content.optionalUri("originalUrl"),
        title = content.optionalText("title"),
    )
