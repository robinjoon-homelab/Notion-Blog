package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.post.Post
import java.time.Instant

sealed interface PostLookupResult {
    data class Found(
        val post: Post,
        val firstPublishedAt: Instant?,
        val lastModifiedAt: Instant,
        // True for the active publication root, which is served canonically at `/`.
        val root: Boolean,
    ) : PostLookupResult

    data object NotFound : PostLookupResult

    data object ContentUnavailable : PostLookupResult
}
