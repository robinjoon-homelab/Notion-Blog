package xyz.robinjoon.notionblog.blog.application.port.output.persistence

import xyz.robinjoon.notionblog.blog.application.model.PublicSeries
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesNavigation
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesSummary
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId

// 시리즈의 공개 글과 그 게시 리비전만 읽는다. excludedPostIds의 글은 공개 글이어도 빠지며, 글 순서는 시리즈 안 위치다.
interface PublishedSeriesStore {
    // 공개 글이 하나 이상인 시리즈만 제목·ID 순으로 반환한다. firstPost의 분류는 채우지 않는다.
    fun findAll(excludedPostIds: Set<PostId>): List<PublicSeriesSummary>

    fun find(
        id: SeriesId,
        excludedPostIds: Set<PostId>,
    ): PublicSeries?

    // 글이 시리즈에 없거나 그 글 자신이 공개 목록에 들지 않으면 null이다. 게시 리비전의 제목만 읽는다.
    fun findNavigation(
        postId: PostId,
        excludedPostIds: Set<PostId>,
    ): PublicSeriesNavigation?
}
