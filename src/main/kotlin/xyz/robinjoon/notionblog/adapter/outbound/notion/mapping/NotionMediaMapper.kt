package xyz.robinjoon.notionblog.adapter.outbound.notion.mapping

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.domain.post.block.content.MediaBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.MediaType
import xyz.robinjoon.notionblog.domain.post.block.media.MediaSource

internal class NotionMediaMapper(
    private val richText: NotionRichTextMapper,
) {
    fun map(
        type: String,
        payload: JsonNode,
    ): MediaBlockContent? =
        when (type) {
            "bookmark" -> MediaBlockContent.Bookmark(payload.safeUri("url"), richText.optional(payload, "caption"))
            "image" -> media(payload, MediaType.IMAGE)
            "video" -> media(payload, MediaType.VIDEO)
            "audio" -> media(payload, MediaType.AUDIO)
            "pdf" -> media(payload, MediaType.PDF)
            "file" -> media(payload, MediaType.FILE)
            "embed" -> MediaBlockContent.Embed(payload.safeUri("url"), richText.optional(payload, "caption"))
            "link_preview" -> MediaBlockContent.LinkPreview(payload.safeUri("url"))
            else -> null
        }

    fun source(payload: JsonNode): MediaSource =
        when (payload.requiredText("type")) {
            "external" -> {
                MediaSource.External(payload.requiredObject("external").safeUri("url"))
            }

            "file" -> {
                val file = payload.requiredObject("file")
                val expiresAt = file.optionalText("expiry_time")?.let(::parseInstant)
                MediaSource.SourceHosted(file.safeUri("url"), expiresAt)
            }

            else -> {
                throw NotionBlockMappingException("media source is unsupported")
            }
        }

    private fun media(
        payload: JsonNode,
        mediaType: MediaType,
    ): MediaBlockContent.Media =
        MediaBlockContent.Media(
            mediaType = mediaType,
            source = source(payload),
            fileName = payload.optionalText("name"),
            caption = richText.optional(payload, "caption"),
        )

    fun icon(node: JsonNode?): BlockIcon? {
        if (node == null) return null
        node.optionalText("emoji")?.let { return BlockIcon.Emoji(it) }
        node.optionalObject("icon")?.let { nativeIcon ->
            return BlockIcon.Native(
                name = nativeIcon.requiredText("name"),
                color = NotionStyleMapper.nativeIconColor(nativeIcon.requiredText("color")),
            )
        }
        node.optionalObject("custom_emoji")?.let { customEmoji ->
            return BlockIcon.CustomEmoji(
                externalId = customEmoji.requiredText("id"),
                name = customEmoji.requiredText("name"),
                source = MediaSource.External(customEmoji.safeUri("url")),
            )
        }
        val file = node.optionalObject("file") ?: node.optionalObject("external") ?: return null
        return BlockIcon.Media(iconSource(node, file))
    }

    private fun iconSource(
        node: JsonNode,
        file: JsonNode,
    ): MediaSource =
        if (node.has("file")) {
            MediaSource.SourceHosted(file.safeUri("url"), file.optionalText("expiry_time")?.let(::parseInstant))
        } else {
            MediaSource.External(file.safeUri("url"))
        }
}
