package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.domain.post.block.content.SpecialBlockContent

internal val specialBlockKinds = setOf("meeting_notes")

internal fun kindOfSpecial(content: SpecialBlockContent): String =
    when (content) {
        is SpecialBlockContent.MeetingNotes -> "meeting_notes"
    }

internal fun toJsonSpecial(content: SpecialBlockContent): ObjectNode =
    when (content) {
        is SpecialBlockContent.MeetingNotes -> encodeMeetingNotes(content)
    }

internal fun fromJsonSpecial(
    kind: String,
    content: ObjectNode,
): SpecialBlockContent =
    when (kind) {
        "meeting_notes" -> decodeMeetingNotes(kind, content)
        else -> throw IllegalArgumentException("unsupported special block kind: $kind")
    }

private fun encodeMeetingNotes(content: SpecialBlockContent.MeetingNotes): ObjectNode =
    objectNode().apply {
        put("title", content.title)
        put("status", content.status.logicalValue())
        set("summary", toJsonInlineList(content.summary))
        set("notesReference", nullableJson(content.notesReference, ::toJsonLink))
    }

private fun decodeMeetingNotes(
    kind: String,
    content: ObjectNode,
): SpecialBlockContent.MeetingNotes =
    SpecialBlockContent.MeetingNotes(
        content.requiredText("title", kind),
        content.requiredEnum("status", kind, ::meetingNotesStatus),
        content.requiredInlineList("summary", kind),
        content.optionalObject("notesReference")?.let(::fromJsonLink),
    )
