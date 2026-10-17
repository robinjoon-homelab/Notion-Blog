package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.home.HomePostOrder

data class HomePageQuery(
    val page: Int = 1,
    val categoryId: CategoryId? = null,
) {
    init {
        require(page >= 1) { "page number must be at least 1" }
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
)
