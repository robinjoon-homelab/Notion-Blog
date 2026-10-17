package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostCategoryTable
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.PostId

// 글의 분류 연결만 읽고 쓴다. 게시 상태 쓰기와 분리해 둬서 게시 저장이 분류 행을 건드리지 않는다.
internal object ExposedPostCategories {
    fun read(postIds: Collection<PostId>): Map<PostId, Set<CategoryId>> {
        if (postIds.isEmpty()) return emptyMap()

        return PostCategoryTable
            .select(PostCategoryTable.postId, PostCategoryTable.categoryId)
            .where { PostCategoryTable.postId inList postIds.map(PostId::value) }
            .groupBy({ PostId(it[PostCategoryTable.postId]) }) { CategoryId(it[PostCategoryTable.categoryId]) }
            .mapValues { (_, ids) -> ids.toSet() }
    }

    // 선택이 바뀐 연결만 지우고 넣어, 그대로인 연결은 다시 쓰지 않는다.
    fun replace(
        postId: PostId,
        categoryIds: Set<CategoryId>,
    ) {
        val current = read(listOf(postId))[postId].orEmpty()
        val removed = (current - categoryIds).map(CategoryId::value)
        if (removed.isNotEmpty()) {
            PostCategoryTable.deleteWhere { (PostCategoryTable.postId eq postId.value) and (categoryId inList removed) }
        }
        PostCategoryTable.batchInsert(categoryIds - current) { categoryId ->
            this[PostCategoryTable.postId] = postId.value
            this[PostCategoryTable.categoryId] = categoryId.value
        }
    }
}
