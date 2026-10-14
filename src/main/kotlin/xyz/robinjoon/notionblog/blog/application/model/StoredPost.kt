package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.Post
import java.time.Instant

data class StoredPost(
    val post: Post,
    val sourceRevision: SourceRevision,
    val capturedAt: Instant,
)
