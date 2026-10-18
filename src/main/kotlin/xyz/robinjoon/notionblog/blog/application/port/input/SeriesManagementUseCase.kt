package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.SeriesChangeResult
import xyz.robinjoon.notionblog.blog.application.model.SeriesLookupResult
import xyz.robinjoon.notionblog.blog.application.model.SeriesOverview
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId

// 시리즈를 만들고 고치고 지운다. 어느 명령도 글·태그·게시 상태를 바꾸거나 글을 지우지 않는다. 제목·설명 길이나 중복 글 ID가 규칙에
// 맞지 않으면 IllegalArgumentException을 던지므로 호출자가 먼저 검증한다.
interface SeriesManagementUseCase {
    // 제목·ID 순이다.
    fun list(): List<SeriesOverview>

    fun get(id: SeriesId): SeriesLookupResult

    fun create(
        title: String,
        description: String,
    ): SeriesChangeResult

    // 제목·설명과 소속 글 전체를 postIds 순서로 한 번에 바꾼다. 빠진 글은 시리즈에서만 빠지고, 다른 시리즈의 글은 그 시리즈에서 옮겨 온다.
    fun save(
        id: SeriesId,
        title: String,
        description: String,
        postIds: List<PostId>,
    ): SeriesChangeResult

    fun delete(id: SeriesId): SeriesChangeResult
}
