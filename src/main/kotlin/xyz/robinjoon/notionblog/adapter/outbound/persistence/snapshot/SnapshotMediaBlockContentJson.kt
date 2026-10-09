package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.domain.post.block.content.MediaBlockContent

internal val mediaBlockKinds = setOf("media", "bookmark", "link_preview", "embed")

internal fun kindOfMediaBlock(content: MediaBlockContent): String =
    when (content) {
        is MediaBlockContent.Media -> "media"
        is MediaBlockContent.Bookmark -> "bookmark"
        is MediaBlockContent.LinkPreview -> "link_preview"
        is MediaBlockContent.Embed -> "embed"
    }

internal fun toJsonMediaBlock(content: MediaBlockContent): ObjectNode =
    when (content) {
        is MediaBlockContent.Media -> encodeMedia(content)
        is MediaBlockContent.Bookmark -> encodeBookmark(content)
        is MediaBlockContent.LinkPreview -> encodeLinkPreview(content)
        is MediaBlockContent.Embed -> encodeEmbed(content)
    }

internal fun fromJsonMediaBlock(
    kind: String,
    content: ObjectNode,
): MediaBlockContent =
    when (kind) {
        "media" -> decodeMedia(kind, content)
        "bookmark" -> MediaBlockContent.Bookmark(content.requiredUri("url", kind), content.requiredInlineList("caption", kind))
        "link_preview" -> MediaBlockContent.LinkPreview(content.requiredUri("url", kind))
        "embed" -> MediaBlockContent.Embed(content.requiredUri("url", kind), content.requiredInlineList("caption", kind))
        else -> throw IllegalArgumentException("unsupported mediablock block kind: $kind")
    }

private fun encodeMedia(content: MediaBlockContent.Media): ObjectNode =
    objectNode().apply {
        put("mediaType", content.mediaType.logicalValue())
        set("source", toJsonMediaSource(content.source))
        set("fileName", nullableJson(content.fileName, ::jsonString))
        set("caption", toJsonInlineList(content.caption))
    }

private fun encodeBookmark(content: MediaBlockContent.Bookmark): ObjectNode =
    objectNode().apply {
        put("url", content.url.toString())
        set("caption", toJsonInlineList(content.caption))
    }

private fun encodeLinkPreview(content: MediaBlockContent.LinkPreview): ObjectNode =
    objectNode().apply {
        put("url", content.url.toString())
    }

private fun encodeEmbed(content: MediaBlockContent.Embed): ObjectNode =
    objectNode().apply {
        put("url", content.url.toString())
        set("caption", toJsonInlineList(content.caption))
    }

private fun decodeMedia(
    kind: String,
    content: ObjectNode,
): MediaBlockContent.Media =
    MediaBlockContent.Media(
        content.requiredEnum("mediaType", kind, ::mediaType),
        fromJsonMediaSource(content.requiredObject("source", kind)),
        content.optionalText("fileName"),
        content.requiredInlineList("caption", kind),
    )
