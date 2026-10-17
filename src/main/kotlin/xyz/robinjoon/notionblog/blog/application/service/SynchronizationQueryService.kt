package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.MediaRepairTarget
import xyz.robinjoon.notionblog.blog.application.model.PostSynchronizationContext
import xyz.robinjoon.notionblog.blog.application.port.input.SynchronizationQueryUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import java.time.Instant

@Transactional(readOnly = true)
class SynchronizationQueryService(
    private val posts: PostRepository,
    private val syncStates: SyncStateRepository,
) : SynchronizationQueryUseCase {
    @Transactional(readOnly = true)
    override fun findDueTargets(
        now: Instant,
        limit: Int,
    ): List<SyncTarget> {
        require(limit > 0) { "due target limit must be positive" }
        return syncStates.findDue(now, limit).map { it.target }
    }

    // 보관된 글과 관리되지 않는 글은 동기화하지 않는다.
    @Transactional(readOnly = true)
    fun loadPost(postId: PostId): PostSynchronizationContext? {
        val post = posts.find(postId)?.takeUnless { it.publication.archived } ?: return null
        val shownRevisionIds = setOfNotNull(post.latestRevisionId, post.publication.publishedRevisionId)
        val summaries = posts.findRevisionSummaries(shownRevisionIds)
        val repairs =
            posts.findUnboundMedia(shownRevisionIds).mapNotNull { (revisionId, media) ->
                summaries[revisionId]?.let { MediaRepairTarget(revisionId, it.sourceRevision, media) }
            }
        return PostSynchronizationContext(
            postId = postId,
            sourceDocument = post.source,
            latestSourceRevision = post.latestRevisionId?.let(summaries::get)?.sourceRevision,
            pendingRepairs = repairs,
        )
    }
}
