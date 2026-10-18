package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.home.HomePostOrder

// search는 호출자가 앞뒤 공백을 지운 검색어이며, 빈 검색어는 null로 넘긴다.
data class HomePageQuery(
    val page: Int = 1,
    val categoryId: CategoryId? = null,
    val search: String? = null,
) {
    init {
        require(page >= 1) { "page number must be at least 1" }
        require(search == null || (search.isNotEmpty() && search == search.trim())) { "search text must be trimmed and not blank" }
        require(search == null || search.length <= MAX_SEARCH_LENGTH) { "search text must be at most $MAX_SEARCH_LENGTH characters" }
    }

    companion object {
        const val MAX_SEARCH_LENGTH = 100
    }
}

data class ListedCategory(
    val id: CategoryId,
    val name: String,
)

data class PublicCategoryCount(
    val category: ListedCategory,
    val count: Int,
)

data class PublishedListQuery(
    val page: PageQuery,
    val order: HomePostOrder = HomePostOrder.NEWEST_FIRST,
    val categoryId: CategoryId? = null,
    // 공개본 리비전의 제목·요약에서 대소문자를 구분하지 않고 찾는다.
    val search: String? = null,
)
