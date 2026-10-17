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
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostPublicationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostRevisionTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonPostPropertiesCodec
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PublishedPost
import xyz.robinjoon.notionblog.blog.application.model.ResultPage
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
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
        page: PageQuery,
    ): ResultPage<PostListEntry> {
        val total =
            publishedRevisions()
                .select(PostPublicationTable.postId)
                .where { listed(excludedPostIds) }
                .count()
                .toInt()
        val clamped = page.clampTo(total)
        val entries =
            publishedRevisions()
                .select(listColumns)
                .where { listed(excludedPostIds) }
                .newestFirst()
                .limit(clamped.size)
                .offset(clamped.offset)
                .map { it.toListEntry() }
        return ResultPage(entries, clamped.number, clamped.size, total)
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

    private fun Query.newestFirst(): Query = orderBy(PostTable.firstPublishedAt to SortOrder.DESC, PostTable.postId to SortOrder.ASC)

    private fun ResultRow.toPublishedPost(): PublishedPost = PublishedPost(revisions.read(this), firstPublishedAt())

    private fun ResultRow.toListEntry(): PostListEntry =
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
