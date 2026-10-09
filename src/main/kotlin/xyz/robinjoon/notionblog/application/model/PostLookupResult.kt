package xyz.robinjoon.notionblog.application.model

import xyz.robinjoon.notionblog.domain.post.Post

sealed interface PostLookupResult {
    data class Found(
        val post: Post,
    ) : PostLookupResult

    data object NotFound : PostLookupResult

    data object ContentUnavailable : PostLookupResult
}
