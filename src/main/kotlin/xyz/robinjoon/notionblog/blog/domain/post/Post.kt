package xyz.robinjoon.notionblog.blog.domain.post

import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.AggregateRoot
import java.time.Instant
import java.util.UUID

@JvmInline
value class PostId(
    val value: UUID,
)

// 원본 문서 하나에 대한 게시 결정이다. 내용은 글 저장소가 함께 저장하는 불변 리비전에 있다. 동기화는 최신 리비전만 옮기고, 게시 상태는
// 관리자 명령만 바꾼다.
@AggregateRoot
@ConsistentCopyVisibility
data class Post private constructor(
    val id: PostId,
    val source: SourceDocumentRef,
    val latestRevisionId: PostRevisionId?,
    val publication: PostPublication,
) {
    val firstPublishedAt: Instant?
        get() = publication.firstPublishedAt

    fun recordRevision(revisionId: PostRevisionId): Post = copy(latestRevisionId = revisionId)

    fun publish(
        revisionId: PostRevisionId,
        publishedAt: Instant,
    ): Post {
        require(!publication.archived) { "an archived post cannot be published" }
        require(revisionId == latestRevisionId) { "only the latest revision can be published" }
        return copy(publication = publication.published(revisionId, publishedAt))
    }

    fun unpublish(): Post = copy(publication = publication.withdrawn())

    fun archive(): Post = copy(publication = publication.archived())

    fun unarchive(): Post = copy(publication = publication.unarchived())

    companion object {
        fun register(
            id: PostId,
            source: SourceDocumentRef,
        ): Post = Post(id, source, null, PostPublication.initial())

        fun restore(
            id: PostId,
            source: SourceDocumentRef,
            latestRevisionId: PostRevisionId?,
            publication: PostPublication,
        ): Post = Post(id, source, latestRevisionId, publication)
    }
}
