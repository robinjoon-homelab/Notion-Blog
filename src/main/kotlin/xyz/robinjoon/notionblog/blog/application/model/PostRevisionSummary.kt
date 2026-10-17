package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import java.time.Instant

data class PostRevisionSummary(
    val id: PostRevisionId,
    val title: String,
    val sourceRevision: SourceRevision,
    val capturedAt: Instant,
)
