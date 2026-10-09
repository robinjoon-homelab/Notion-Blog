package xyz.robinjoon.notionblog.application.port.output.persistence

import xyz.robinjoon.notionblog.application.model.PostFeedEntry
import xyz.robinjoon.notionblog.application.model.StoredPost
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.domain.publication.PostAvailability
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceRevision
import java.time.Instant

interface PostRepository {
    fun find(postId: PostId): StoredPost?

    fun findRecentPublishedPosts(
        publicationId: PublicationId,
        excludedPostIds: Set<PostId>,
        limit: Int,
    ): List<PostFeedEntry>

    fun recordFirstPublication(
        postId: PostId,
        observedAt: Instant,
    )

    fun findBinding(postId: PostId): PostSourceBinding?

    fun findBinding(sourceDocument: SourceDocumentRef): PostSourceBinding?

    fun findBindingsBySourceDocuments(sourceDocuments: Set<SourceDocumentRef>): Map<SourceDocumentRef, PostSourceBinding>

    fun findBindingsByPostIds(postIds: Set<PostId>): Map<PostId, PostSourceBinding>

    fun saveIdentity(
        binding: PostSourceBinding,
        title: String,
        changedAt: Instant,
    )

    fun saveSnapshot(
        post: Post,
        sourceRevision: SourceRevision,
        capturedAt: Instant,
    )

    fun findAvailability(postId: PostId): PostAvailability?

    fun findAvailabilities(postIds: Set<PostId>): Map<PostId, PostAvailability>

    fun saveAvailability(availability: PostAvailability)

    fun saveAvailabilities(availabilities: Collection<PostAvailability>)

    fun findRenderablePostIds(postIds: Set<PostId>): Set<PostId>
}
