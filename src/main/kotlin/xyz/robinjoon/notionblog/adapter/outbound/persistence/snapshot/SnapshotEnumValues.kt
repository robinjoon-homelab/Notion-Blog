package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import xyz.robinjoon.notionblog.domain.post.block.content.DataCardLayout
import xyz.robinjoon.notionblog.domain.post.block.content.DataCardSize
import xyz.robinjoon.notionblog.domain.post.block.content.DataCoverAspect
import xyz.robinjoon.notionblog.domain.post.block.content.HeadingLevel
import xyz.robinjoon.notionblog.domain.post.block.content.MediaType
import xyz.robinjoon.notionblog.domain.post.block.content.MeetingNotesStatus
import xyz.robinjoon.notionblog.domain.post.block.content.NumberedListFormat
import xyz.robinjoon.notionblog.domain.post.block.inline.MentionKind
import xyz.robinjoon.notionblog.domain.post.block.style.Alignment
import xyz.robinjoon.notionblog.domain.post.block.style.ColorToken

internal fun colorToken(value: String): ColorToken = enumValue(value, "color token") { ColorToken.valueOf(it) }

internal fun alignment(value: String): Alignment = enumValue(value, "alignment") { Alignment.valueOf(it) }

internal fun headingLevel(value: String): HeadingLevel = enumValue(value, "heading level") { HeadingLevel.valueOf(it) }

internal fun numberedListFormat(value: String): NumberedListFormat =
    enumValue(value, "numbered list format") {
        NumberedListFormat.valueOf(it)
    }

internal fun mediaType(value: String): MediaType = enumValue(value, "media type") { MediaType.valueOf(it) }

internal fun meetingNotesStatus(value: String): MeetingNotesStatus =
    enumValue(value, "meeting notes status") {
        MeetingNotesStatus.valueOf(it)
    }

internal fun mentionKind(value: String): MentionKind = enumValue(value, "mention kind") { MentionKind.valueOf(it) }

internal fun dataCardSize(value: String): DataCardSize = enumValue(value, "data card size") { DataCardSize.valueOf(it) }

internal fun dataCoverAspect(value: String): DataCoverAspect = enumValue(value, "data cover aspect") { DataCoverAspect.valueOf(it) }

internal fun dataCardLayout(value: String): DataCardLayout = enumValue(value, "data card layout") { DataCardLayout.valueOf(it) }

private fun <T> enumValue(
    value: String,
    label: String,
    resolver: (String) -> T,
): T =
    try {
        resolver(value.uppercase())
    } catch (exception: IllegalArgumentException) {
        throw IllegalArgumentException("unsupported $label: $value", exception)
    }

internal fun Enum<*>.logicalValue(): String = name.lowercase()
