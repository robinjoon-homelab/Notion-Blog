package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPublishedRows.isPublic
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPublishedRows.listed
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPublishedRows.publishedRevisions
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
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.home.HomePostOrder
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import java.util.Locale

// 공개 글 목록·RSS·태그 조회다. 공개 판정은 ExposedPublishedRows만 한다.
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
        val filter = listed(excludedPostIds) and inCategory(query.categoryId) and matches(query.search)
        val total =
            publishedRevisions()
                .select(PostPublicationTable.postId)
                .where { filter }
                .count()
                .toInt()
        val clamped = query.page.clampTo(total)
        val rows =
            publishedRevisions()
                .select(ExposedPublishedRows.listColumns)
                .where { filter }
                .ordered(query.order)
                .limit(clamped.size)
                .offset(clamped.offset)
                .toList()
        val categories = ExposedPublishedRows.categoriesOf(rows.map { PostId(it[PostPublicationTable.postId]) })
        val entries =
            rows.map { row ->
                ExposedPublishedRows.listEntry(row, propertiesCodec, categories[PostId(row[PostPublicationTable.postId])].orEmpty())
            }
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
            ).map { PublicCategoryCount(ExposedPublishedRows.listedCategory(it), it[count].toInt()) }
    }

    override fun countListed(excludedPostIds: Set<PostId>): Int =
        publishedRevisions()
            .select(PostPublicationTable.postId)
            .where { listed(excludedPostIds) }
            .count()
            .toInt()

    override fun findCategories(postId: PostId): List<ListedCategory> {
        if (findPublicPostIds(setOf(postId)).isEmpty()) return emptyList()

        return ExposedPublishedRows.categoriesOf(listOf(postId))[postId].orEmpty()
    }

    override fun findPublicPostIds(postIds: Set<PostId>): Set<PostId> {
        if (postIds.isEmpty()) return emptySet()

        return PostPublicationTable
            .select(PostPublicationTable.postId)
            .where { isPublic() and (PostPublicationTable.postId inList postIds.map(PostId::value)) }
            .mapTo(linkedSetOf()) { PostId(it[PostPublicationTable.postId]) }
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

    // 게시 리비전의 제목·요약만 찾으므로 아직 게시하지 않은 변경은 검색되지 않는다. 검색어의 %와 _는 문자 그대로 찾는다.
    private fun matches(search: String?): Op<Boolean> {
        if (search == null) return Op.TRUE

        val pattern = LikePattern("%", ESCAPE) + LikePattern.ofLiteral(search.lowercase(Locale.ROOT), ESCAPE) + "%"
        return (PostRevisionTable.title.lowerCase() like pattern) or (PostRevisionTable.summary.lowerCase() like pattern)
    }

    private fun Query.newestFirst(): Query = orderBy(PostTable.firstPublishedAt to SortOrder.DESC, PostTable.postId to SortOrder.ASC)

    // 같은 시각이면 두 방향 모두 글 ID 오름차순으로 순서를 고정한다.
    private fun Query.ordered(order: HomePostOrder): Query =
        when (order) {
            HomePostOrder.NEWEST_FIRST -> newestFirst()
            HomePostOrder.OLDEST_FIRST -> orderBy(PostTable.firstPublishedAt to SortOrder.ASC, PostTable.postId to SortOrder.ASC)
        }

    private fun ResultRow.toPublishedPost(): PublishedPost =
        PublishedPost(revisions.read(this), ExposedPublishedRows.firstPublishedAt(this))

    private companion object {
        const val ESCAPE = '\\'
    }
}
