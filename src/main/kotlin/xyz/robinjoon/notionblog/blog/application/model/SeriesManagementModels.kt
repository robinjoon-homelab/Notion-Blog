package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId

// postCount는 비공개·보관 글을 포함한 소속 글 수, publicPostCount는 그중 공개 글 수다.
data class SeriesOverview(
    val id: SeriesId,
    val title: String,
    val description: String,
    val postCount: Int,
    val publicPostCount: Int,
)

// currentSeriesTitle은 후보 글이 지금 속한 다른 시리즈의 제목이다. 소속 글과 어느 시리즈에도 없는 글은 null이다.
data class SeriesPostSummary(
    val postId: PostId,
    val title: String,
    val status: AdminPostStatus,
    val currentSeriesTitle: String?,
)

// members는 시리즈 순서, candidates는 이 시리즈에 없고 보관되지 않은 관리 대상 글을 제목·ID 순으로 담는다.
data class SeriesEditor(
    val overview: SeriesOverview,
    val members: List<SeriesPostSummary>,
    val candidates: List<SeriesPostSummary>,
)

sealed interface SeriesLookupResult {
    data class Found(
        val editor: SeriesEditor,
    ) : SeriesLookupResult

    data object NotFound : SeriesLookupResult
}

// Saved가 아닌 결과는 아무것도 저장하지 않았다는 뜻이다.
sealed interface SeriesChangeResult {
    data class Saved(
        val id: SeriesId,
    ) : SeriesChangeResult

    data object NotFound : SeriesChangeResult

    // 관리 대상이 아닌 글이 포함되었다.
    data object PostNotFound : SeriesChangeResult
}
