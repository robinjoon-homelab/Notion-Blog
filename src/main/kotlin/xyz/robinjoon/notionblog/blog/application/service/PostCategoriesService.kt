package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.PostCategoryChangeResult
import xyz.robinjoon.notionblog.blog.application.model.PostCategoryLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PostCategorySelection
import xyz.robinjoon.notionblog.blog.application.port.input.PostCategoriesUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.CategoryRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.domain.category.Category
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.PostId

// 글의 분류 연결만 바꾸며 게시 상태·세대·리비전과 Notion 태그는 그대로 둔다. 선택한 분류를 ID 순서로 잠근 뒤 글을 잠근다. 분류
// 삭제도 분류 행을 먼저 잠그므로, 둘이 엇갈려도 연결이 남은 분류가 지워지거나 지워진 분류가 지정되지 않는다.
// Exposed는 SQL 실패를 checked 예외로 알리므로 런타임 실패처럼 명시적으로 롤백한다.
@Transactional(rollbackFor = [Exception::class])
class PostCategoriesService(
    private val posts: PostRepository,
    private val categories: CategoryRepository,
) : PostCategoriesUseCase {
    @Transactional(readOnly = true)
    override fun get(postId: PostId): PostCategoryLookupResult {
        val post = posts.find(postId) ?: return PostCategoryLookupResult.NotFound
        val title = posts.findTitles(setOf(postId))[postId] ?: UNTITLED
        return PostCategoryLookupResult.Found(PostCategorySelection(postId, title, categories.overviews(), post.categoryIds))
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun assign(
        postId: PostId,
        categoryIds: Set<CategoryId>,
    ): PostCategoryChangeResult {
        val locked = categories.lock(categoryIds)
        val post = posts.lock(postId) ?: return PostCategoryChangeResult.PostNotFound
        if (locked.map(Category::id).toSet() != categoryIds) return PostCategoryChangeResult.CategoryNotFound
        posts.saveCategories(post.categorize(categoryIds))
        return PostCategoryChangeResult.Saved
    }

    private companion object {
        const val UNTITLED = "Untitled"
    }
}
