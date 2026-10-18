package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesListLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesLookupResult
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId

// 공개 글이 하나 이상인 시리즈만 다루며 공개 글만 보여 준다.
interface GetPublicSeriesUseCase {
    // 제목·ID 순이다.
    fun list(): PublicSeriesListLookupResult

    fun get(id: SeriesId): PublicSeriesLookupResult
}
