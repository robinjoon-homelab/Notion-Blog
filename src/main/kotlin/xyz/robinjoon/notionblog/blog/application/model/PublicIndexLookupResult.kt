package xyz.robinjoon.notionblog.blog.application.model

sealed interface PublicIndexLookupResult {
    data class Found(
        val index: PublicIndex,
    ) : PublicIndexLookupResult

    data object ContentUnavailable : PublicIndexLookupResult
}
