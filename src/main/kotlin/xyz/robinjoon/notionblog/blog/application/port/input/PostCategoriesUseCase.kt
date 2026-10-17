package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.PostCategoryChangeResult
import xyz.robinjoon.notionblog.blog.application.model.PostCategoryLookupResult
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.PostId

// 글의 분류 연결만 다룬다. 게시 상태·세대·리비전과 Notion 태그는 바꾸지 않는다.
interface PostCategoriesUseCase {
    fun get(postId: PostId): PostCategoryLookupResult

    // 선택한 분류 전체로 한 번에 바꾼다. 빈 집합이면 연결을 모두 지운다.
    fun assign(
        postId: PostId,
        categoryIds: Set<CategoryId>,
    ): PostCategoryChangeResult
}
