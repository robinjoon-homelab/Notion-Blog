package xyz.robinjoon.notionblog.application.model

sealed interface PostFeedLookupResult {
    data class Found(val feed: PostFeed) : PostFeedLookupResult

    data object ContentUnavailable : PostFeedLookupResult
}
