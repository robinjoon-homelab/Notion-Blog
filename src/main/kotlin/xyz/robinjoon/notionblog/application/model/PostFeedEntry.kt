package xyz.robinjoon.notionblog.application.model

import xyz.robinjoon.notionblog.domain.post.Post
import java.time.Instant

data class PostFeedEntry(
    val post: Post,
    val firstPublishedAt: Instant,
)
