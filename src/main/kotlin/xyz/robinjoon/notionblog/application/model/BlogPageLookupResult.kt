package xyz.robinjoon.notionblog.application.model

sealed interface BlogPageLookupResult {
    data class Found(
        val page: BlogPage,
    ) : BlogPageLookupResult

    data object NotFound : BlogPageLookupResult

    data object ContentUnavailable : BlogPageLookupResult
}
