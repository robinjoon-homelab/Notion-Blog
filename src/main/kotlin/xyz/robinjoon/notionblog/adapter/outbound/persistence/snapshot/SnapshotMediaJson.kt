package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.domain.post.block.media.MediaSource

internal fun toJsonMediaSource(source: MediaSource): ObjectNode =
    objectNode().apply {
        when (source) {
            is MediaSource.External -> {
                put("kind", "external")
                put("url", source.url.toString())
            }

            is MediaSource.SourceHosted -> {
                put("kind", "source_hosted")
                put("url", source.url.toString())
                set("expiresAt", nullableJson(source.expiresAt) { jsonString(it.toString()) })
            }
        }
    }

internal fun fromJsonMediaSource(node: ObjectNode): MediaSource =
    when (val kind = node.requiredText("kind", "media source")) {
        "external" -> MediaSource.External(node.requiredUri("url", kind))
        "source_hosted" -> MediaSource.SourceHosted(node.requiredUri("url", kind), node.optionalInstant("expiresAt"))
        else -> throw IllegalArgumentException("unsupported media source kind: $kind")
    }

internal fun toJsonIcon(icon: BlockIcon): ObjectNode =
    objectNode().apply {
        when (icon) {
            is BlockIcon.Emoji -> {
                put("kind", "emoji")
                put("value", icon.value)
            }

            is BlockIcon.Media -> {
                put("kind", "media")
                set("source", toJsonMediaSource(icon.source))
            }

            is BlockIcon.Native -> {
                put("kind", "native")
                put("name", icon.name)
                set("color", nullableJson(icon.color) { jsonString(it.logicalValue()) })
            }

            is BlockIcon.CustomEmoji -> {
                put("kind", "custom_emoji")
                put("externalId", icon.externalId)
                put("name", icon.name)
                set("source", toJsonMediaSource(icon.source))
            }
        }
    }

internal fun fromJsonIcon(node: ObjectNode): BlockIcon =
    when (val kind = node.requiredText("kind", "block icon")) {
        "emoji" -> {
            BlockIcon.Emoji(node.requiredText("value", kind))
        }

        "media" -> {
            BlockIcon.Media(fromJsonMediaSource(node.requiredObject("source", kind)))
        }

        "native" -> {
            BlockIcon.Native(node.requiredText("name", kind), node.optionalEnum("color", ::colorToken))
        }

        "custom_emoji" -> {
            BlockIcon.CustomEmoji(
                externalId = node.requiredText("externalId", kind),
                name = node.requiredText("name", kind),
                source = fromJsonExternalMediaSource(node.requiredObject("source", kind)),
            )
        }

        else -> {
            throw IllegalArgumentException("unsupported block icon kind: $kind")
        }
    }

private fun fromJsonExternalMediaSource(node: ObjectNode): MediaSource.External {
    val source = fromJsonMediaSource(node)
    require(source is MediaSource.External) { "custom emoji source must be external" }
    return source
}
