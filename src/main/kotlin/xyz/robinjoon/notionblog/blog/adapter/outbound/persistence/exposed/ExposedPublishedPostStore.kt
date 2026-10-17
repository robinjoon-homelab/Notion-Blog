package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.CategoryTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostCategoryTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostPublicationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostRevisionTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonPostPropertiesCodec
import xyz.robinjoon.notionblog.blog.application.model.ListedCategory
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PublicCategoryCount
import xyz.robinjoon.notionblog.blog.application.model.PublishedListQuery
import xyz.robinjoon.notionblog.blog.application.model.PublishedPost
import xyz.robinjoon.notionblog.blog.application.model.ResultPage
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.home.HomePostOrder
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility

// 유일한 공개 관문이다. PUBLIC 게시 상태와 그 상태가 게시한 리비전만 보며 원본 상태는 전혀 참고하지 않는다.
class ExposedPublishedPostStore(
    private val revisions: ExposedRevisionMapper,
    private val propertiesCodec: JsonPostPropertiesCodec,
) : PublishedPostStore {
    override fun find(postId: PostId): PublishedPost? =
        publishedRevisions()
            .selectAll()
            .where { isPublic() and (PostPublicationTable.postId eq postId.value) }
            .singleOrNull()
            ?.toPublishedPost()

    override fun findRecent(
        excludedPostIds: Set<PostId>,
        limit: Int,
    ): List<PublishedPost> {
        require(limit > 0) { "recent post limit must be positive" }
        return publishedRevisions()
            .selectAll()
            .where { listed(excludedPostIds) }
            .newestFirst()
            .limit(limit)
            .map { it.toPublishedPost() }
    }

    override fun findListPage(
        excludedPostIds: Set<PostId>,
        query: PublishedListQuery,
    ): ResultPage<PostListEntry> {
        val filter = listed(excludedPostIds) and inCategory(query.categoryId)
        val total =
            publishedRevisions()
                .select(PostPublicationTable.postId)
                .where { filter }
                .count()
                .toInt()
        val clamped = query.page.clampTo(total)
        val rows =
            publishedRevisions()
                .select(listColumns)
                .where { filter }
                .ordered(query.order)
                .limit(clamped.size)
                .offset(clamped.offset)
                .toList()
        val categories = categoriesOf(rows.map { PostId(it[PostPublicationTable.postId]) })
        val entries = rows.map { it.toListEntry(categories[PostId(it[PostPublicationTable.postId])].orEmpty()) }
        return ResultPage(entries, clamped.number, clamped.size, total)
    }

    // 공개 글에 붙은 분류만 센다. 비공개 글과 제외 글은 개수에 들어가지 않는다.
    override fun findCategoryCounts(excludedPostIds: Set<PostId>): List<PublicCategoryCount> {
        val count = PostCategoryTable.postId.count()
        return PostCategoryTable
            .join(CategoryTable, JoinType.INNER, PostCategoryTable.categoryId, CategoryTable.categoryId)
            .join(PostPublicationTable, JoinType.INNER, PostCategoryTable.postId, PostPublicationTable.postId)
            .select(CategoryTable.categoryId, CategoryTable.name, CategoryTable.position, count)
            .where { listed(excludedPostIds) }
            .groupBy(CategoryTable.categoryId, CategoryTable.name, CategoryTable.position)
            .orderBy(
                CategoryTable.position to SortOrder.ASC,
                CategoryTable.name to SortOrder.ASC,
                CategoryTable.categoryId to SortOrder.ASC,
            ).map { PublicCategoryCount(it.toListedCategory(), it[count].toInt()) }
    }

    override fun findPublicPostIds(postIds: Set<PostId>): Set<PostId> {
        if (postIds.isEmpty()) return emptySet()

        return PostPublicationTable
            .select(PostPublicationTable.postId)
            .where { isPublic() and (PostPublicationTable.postId inList postIds.map(PostId::value)) }
            .mapTo(linkedSetOf()) { PostId(it[PostPublicationTable.postId]) }
    }

    private fun publishedRevisions(): Join =
        PostPublicationTable
            .join(
                PostRevisionTable,
                JoinType.INNER,
                additionalConstraint = { PostPublicationTable.publishedRevisionId eq PostRevisionTable.revisionId },
            ).join(PostTable, JoinType.INNER, PostPublicationTable.postId, PostTable.postId)

    private fun isPublic(): Op<Boolean> = PostPublicationTable.visibility eq PostVisibility.PUBLIC.name

    private fun listed(excludedPostIds: Set<PostId>): Op<Boolean> =
        if (excludedPostIds.isEmpty()) {
            isPublic()
        } else {
            isPublic() and (PostPublicationTable.postId notInList excludedPostIds.map(PostId::value))
        }

    // 여러 분류를 가진 글이 행으로 늘어나 LIMIT 앞에서 중복되지 않도록 조인 대신 서브쿼리로 거른다.
    private fun inCategory(categoryId: CategoryId?): Op<Boolean> =
        if (categoryId == null) {
            Op.TRUE
        } else {
            PostPublicationTable.postId inSubQuery
                PostCategoryTable
                    .select(PostCategoryTable.postId)
                    .where { PostCategoryTable.categoryId eq categoryId.value }
        }

    private fun Query.newestFirst(): Query = orderBy(PostTable.firstPublishedAt to SortOrder.DESC, PostTable.postId to SortOrder.ASC)

    // 같은 시각이면 두 방향 모두 글 ID 오름차순으로 순서를 고정한다.
    private fun Query.ordered(order: HomePostOrder): Query =
        when (order) {
            HomePostOrder.NEWEST_FIRST -> newestFirst()
            HomePostOrder.OLDEST_FIRST -> orderBy(PostTable.firstPublishedAt to SortOrder.ASC, PostTable.postId to SortOrder.ASC)
        }

    private fun categoriesOf(postIds: List<PostId>): Map<PostId, List<ListedCategory>> {
        if (postIds.isEmpty()) return emptyMap()

        return PostCategoryTable
            .join(CategoryTable, JoinType.INNER, PostCategoryTable.categoryId, CategoryTable.categoryId)
            .select(PostCategoryTable.postId, CategoryTable.categoryId, CategoryTable.name)
            .where { PostCategoryTable.postId inList postIds.map(PostId::value) }
            .orderBy(
                CategoryTable.position to SortOrder.ASC,
                CategoryTable.name to SortOrder.ASC,
                CategoryTable.categoryId to SortOrder.ASC,
            ).groupBy({ PostId(it[PostCategoryTable.postId]) }) { it.toListedCategory() }
    }

    private fun ResultRow.toListedCategory(): ListedCategory =
        ListedCategory(CategoryId(this[CategoryTable.categoryId]), this[CategoryTable.name])

    private fun ResultRow.toPublishedPost(): PublishedPost = PublishedPost(revisions.read(this), firstPublishedAt())

    private fun ResultRow.toListEntry(categories: List<ListedCategory>): PostListEntry =
        try {
            PostListEntry(
                postId = PostId(this[PostPublicationTable.postId]),
                revisionId = PostRevisionId(this[PostRevisionTable.revisionId]),
                properties =
                    PostProperties(
                        title = this[PostRevisionTable.title],
                        summary = this[PostRevisionTable.summary],
                        tags = propertiesCodec.decodeTags(this[PostRevisionTable.tagsJson]),
                        cover = propertiesCodec.decodeCover(this[PostRevisionTable.coverJson]),
                    ),
                firstPublishedAt = firstPublishedAt(),
                categories = categories,
            )
        } catch (exception: IllegalArgumentException) {
            throw SnapshotContentException("unable to decode list entry ${this[PostPublicationTable.postId]}", exception)
        }

    private fun ResultRow.firstPublishedAt() =
        requireNotNull(this[PostTable.firstPublishedAt]) { "a public post must have a first publication time" }.toInstant()

    private companion object {
        val listColumns =
            listOf(
                PostPublicationTable.postId,
                PostRevisionTable.revisionId,
                PostRevisionTable.title,
                PostRevisionTable.summary,
                PostRevisionTable.tagsJson,
                PostRevisionTable.coverJson,
                PostTable.firstPublishedAt,
                PostTable.postId,
            )
    }
}
