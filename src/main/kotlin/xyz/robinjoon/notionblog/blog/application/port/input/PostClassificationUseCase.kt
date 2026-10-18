package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.PostClassificationChangeResult
import xyz.robinjoon.notionblog.blog.application.model.PostClassificationLookupResult
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId

// 글의 태그와 시리즈 소속만 다룬다. 본문·게시 상태·세대·리비전과 Notion 원문 태그는 바꾸지 않는다.
interface PostClassificationUseCase {
    fun get(postId: PostId): PostClassificationLookupResult

    // 한 번에 저장하며 Saved가 아니면 아무것도 바꾸지 않는다. 태그는 categoryIds와 옮긴 원문 태그로 바꾸고(빈 집합이면 모두 해제),
    // 원문 태그는 대소문자를 구분하지 않고 이름이 같은 태그를 쓰거나 새로 만든다. seriesId가 지금과 다르면 그 시리즈의 끝으로 옮기고,
    // null이면 소속을 해제한다.
    fun save(
        postId: PostId,
        categoryIds: Set<CategoryId>,
        seriesId: SeriesId?,
        sourceTagNames: Set<String>,
    ): PostClassificationChangeResult
}
