package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.select
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.CategoryTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostCategoryTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostPublicationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostRevisionTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonPostPropertiesCodec
import xyz.robinjoon.notionblog.blog.application.model.ListedCategory
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility
import java.time.Instant

// 유일한 공개 판정이다. 공개 글 Store들은 PUBLIC 게시 상태와 그 상태가 게시한 리비전만 이 조인으로 보며 원본 상태는 전혀 참고하지 않는다.
internal object ExposedPublishedRows {
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

    fun publishedRevisions(): Join =
        PostPublicationTable
            .join(
                PostRevisionTable,
                JoinType.INNER,
                additionalConstraint = { PostPublicationTable.publishedRevisionId eq PostRevisionTable.revisionId },
            ).join(PostTable, JoinType.INNER, PostPublicationTable.postId, PostTable.postId)

    fun isPublic(): Op<Boolean> = PostPublicationTable.visibility eq PostVisibility.PUBLIC.name

    fun listed(excludedPostIds: Set<PostId>): Op<Boolean> =
        if (excludedPostIds.isEmpty()) {
            isPublic()
        } else {
            isPublic() and (PostPublicationTable.postId notInList excludedPostIds.map(PostId::value))
        }

    // 표시 순서, 이름, ID 순이다.
    fun categoriesOf(postIds: List<PostId>): Map<PostId, List<ListedCategory>> {
        if (postIds.isEmpty()) return emptyMap()

        return PostCategoryTable
            .join(CategoryTable, JoinType.INNER, PostCategoryTable.categoryId, CategoryTable.categoryId)
            .select(PostCategoryTable.postId, CategoryTable.categoryId, CategoryTable.name)
            .where { PostCategoryTable.postId inList postIds.map(PostId::value) }
            .orderBy(
                CategoryTable.position to SortOrder.ASC,
                CategoryTable.name to SortOrder.ASC,
                CategoryTable.categoryId to SortOrder.ASC,
            ).groupBy({ PostId(it[PostCategoryTable.postId]) }) { listedCategory(it) }
    }

    fun listedCategory(row: ResultRow): ListedCategory = ListedCategory(CategoryId(row[CategoryTable.categoryId]), row[CategoryTable.name])

    fun listEntry(
        row: ResultRow,
        propertiesCodec: JsonPostPropertiesCodec,
        categories: List<ListedCategory>,
    ): PostListEntry =
        try {
            PostListEntry(
                postId = PostId(row[PostPublicationTable.postId]),
                revisionId = PostRevisionId(row[PostRevisionTable.revisionId]),
                properties =
                    PostProperties(
                        title = row[PostRevisionTable.title],
                        summary = row[PostRevisionTable.summary],
                        tags = propertiesCodec.decodeTags(row[PostRevisionTable.tagsJson]),
                        cover = propertiesCodec.decodeCover(row[PostRevisionTable.coverJson]),
                    ),
                firstPublishedAt = firstPublishedAt(row),
                categories = categories,
            )
        } catch (exception: IllegalArgumentException) {
            throw SnapshotContentException("unable to decode list entry ${row[PostPublicationTable.postId]}", exception)
        }

    fun firstPublishedAt(row: ResultRow): Instant =
        requireNotNull(row[PostTable.firstPublishedAt]) { "a public post must have a first publication time" }.toInstant()
}
