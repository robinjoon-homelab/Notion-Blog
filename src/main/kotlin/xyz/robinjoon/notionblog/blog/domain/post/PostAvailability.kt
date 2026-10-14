package xyz.robinjoon.notionblog.blog.domain.post

import java.time.Instant

data class PostAvailability(
    val postId: PostId,
    val status: PostAvailabilityStatus,
    val confirmedAt: Instant,
)

enum class PostAvailabilityStatus {
    PUBLISHED,
    UNPUBLISHED,
}
