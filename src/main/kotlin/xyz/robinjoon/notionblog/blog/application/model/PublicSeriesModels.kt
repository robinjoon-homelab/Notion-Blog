package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId

// 공개 글이 하나 이상인 시리즈다. postCount는 공개 글만 세고, 대표 이미지는 시리즈 순서상 첫 공개 글의 공개본 표지다.
data class PublicSeriesSummary(
    val id: SeriesId,
    val title: String,
    val description: String,
    val postCount: Int,
    val firstPost: PostListEntry,
)

// posts는 공개 글만 시리즈 순서로 담는다. 비공개 글의 제목·개수·순번을 드러내지 않도록 빈 시리즈는 만들지 않는다.
data class PublicSeries(
    val id: SeriesId,
    val title: String,
    val description: String,
    val posts: List<PostListEntry>,
) {
    init {
        require(posts.isNotEmpty()) { "a public series must have a public post" }
    }
}

// title은 공개본 리비전의 제목이다.
data class SeriesPostLink(
    val postId: PostId,
    val title: String,
)

// posts는 공개 글만 담으므로 순번·개수·이전/다음 글에 비공개 글이 끼지 않는다.
data class PublicSeriesNavigation(
    val seriesId: SeriesId,
    val title: String,
    val posts: List<SeriesPostLink>,
    val currentPostId: PostId,
) {
    init {
        require(posts.any { it.postId == currentPostId }) { "the current post must be listed in its series" }
    }

    // 0부터 센다.
    val currentIndex: Int
        get() = posts.indexOfFirst { it.postId == currentPostId }

    val previous: SeriesPostLink?
        get() = posts.getOrNull(currentIndex - 1)

    val next: SeriesPostLink?
        get() = posts.getOrNull(currentIndex + 1)
}

data class PublicSeriesListPage(
    val chrome: SitePresentationContext,
    val series: List<PublicSeriesSummary>,
    val references: ContentReferences,
)

data class PublicSeriesPage(
    val chrome: SitePresentationContext,
    val series: PublicSeries,
    val references: ContentReferences,
    val configuration: HomeConfiguration,
)

sealed interface PublicSeriesListLookupResult {
    data class Found(
        val page: PublicSeriesListPage,
    ) : PublicSeriesListLookupResult

    data object ContentUnavailable : PublicSeriesListLookupResult
}

sealed interface PublicSeriesLookupResult {
    data class Found(
        val page: PublicSeriesPage,
    ) : PublicSeriesLookupResult

    // 없는 시리즈와 공개 글이 하나도 없는 시리즈를 구별하지 않는다.
    data object NotFound : PublicSeriesLookupResult

    data object ContentUnavailable : PublicSeriesLookupResult
}
