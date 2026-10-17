package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.PostId

// assignedPosts는 초안·보관 글까지 센다. 0이 아니면 삭제할 수 없다.
data class CategoryOverview(
    val id: CategoryId,
    val name: String,
    val position: Int,
    val assignedPosts: Int,
)

sealed interface CategoryChangeResult {
    data class Saved(
        val id: CategoryId,
    ) : CategoryChangeResult

    data object NotFound : CategoryChangeResult

    // 대소문자만 다른 이름도 같은 이름으로 본다.
    data object NameInUse : CategoryChangeResult

    data object AssignedToPosts : CategoryChangeResult
}

data class PostCategorySelection(
    val postId: PostId,
    val title: String,
    val categories: List<CategoryOverview>,
    val selected: Set<CategoryId>,
)

sealed interface PostCategoryLookupResult {
    data class Found(
        val selection: PostCategorySelection,
    ) : PostCategoryLookupResult

    data object NotFound : PostCategoryLookupResult
}

sealed interface PostCategoryChangeResult {
    data object Saved : PostCategoryChangeResult

    data object PostNotFound : PostCategoryChangeResult

    data object CategoryNotFound : PostCategoryChangeResult
}
