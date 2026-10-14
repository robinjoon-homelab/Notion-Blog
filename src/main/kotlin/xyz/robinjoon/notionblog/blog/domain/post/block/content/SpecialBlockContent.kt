package xyz.robinjoon.notionblog.blog.domain.post.block.content

import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget

sealed interface SpecialBlockContent : BlockContent {
    data class MeetingNotes(
        val title: String,
        val status: MeetingNotesStatus,
        val summary: List<InlineContent>,
        val notesReference: LinkTarget?,
    ) : SpecialBlockContent {
        init {
            require(title.isNotBlank()) { "meeting notes title must not be blank" }
        }
    }
}

enum class MeetingNotesStatus {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED,
    OTHER,
}
