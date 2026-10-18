package xyz.robinjoon.notionblog.blog.application.port.output.persistence

import xyz.robinjoon.notionblog.blog.domain.series.Series
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId

// 관리자가 만든 시리즈와 순서 있는 소속 글이다. 글 하나가 두 시리즈에 속하지 않는 규칙은 DB 유일 제약으로도 보장한다.
interface SeriesRepository {
    // 제목, ID 순이다.
    fun findAll(): List<Series>

    fun find(id: SeriesId): Series?

    // 있는 시리즈 행만 ID 순서로 잠가 반환하므로 일부가 없으면 목록이 짧아진다. 모든 명령이 같은 순서로 잠가 교착을 피한다.
    fun lock(ids: Set<SeriesId>): List<Series>

    // 없으면 추가하고 있으면 제목·설명을 바꾸며 소속 행을 series.postIds 순서로 맞춘다. 다른 시리즈에 저장된 글이 있으면
    // SeriesMembershipConflictException을 던진다.
    fun save(series: Series)

    // 소속 행과 시리즈 행만 지운다. 글은 지우지 않는다.
    fun delete(id: SeriesId)
}

class SeriesMembershipConflictException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
