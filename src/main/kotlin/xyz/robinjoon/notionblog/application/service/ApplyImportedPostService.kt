package xyz.robinjoon.notionblog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.application.model.ImportedPost
import xyz.robinjoon.notionblog.application.model.ImportedPublicationStatus
import xyz.robinjoon.notionblog.application.port.output.diagnostics.SnapshotFailureOperation
import xyz.robinjoon.notionblog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.domain.publication.PostAvailability
import xyz.robinjoon.notionblog.domain.publication.PostAvailabilityStatus
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.sync.RefreshPolicy
import xyz.robinjoon.notionblog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.domain.sync.SyncState
import xyz.robinjoon.notionblog.domain.sync.SyncTarget
import java.time.Clock
import java.time.Instant

@Transactional
class ApplyImportedPostService(
    private val postRepository: PostRepository,
    private val syncStateRepository: SyncStateRepository,
    private val clock: Clock,
    private val refreshPolicy: RefreshPolicy,
    private val snapshotFailures: SnapshotFailureReporter,
    private val postIdFactory: (SourceDocumentRef) -> PostId,
) {
    @Transactional
    fun apply(imported: ImportedPost): PostId {
        val now = clock.instant()
        val binding =
            postRepository.findBinding(imported.sourceDocument)
                ?: PostSourceBinding(postIdFactory(imported.sourceDocument), imported.sourceDocument)

        postRepository.saveIdentity(binding, imported.title, now)

        when (imported.publicationStatus) {
            ImportedPublicationStatus.PUBLISHED -> {
                if (requiresSnapshotRewrite(binding.postId, imported)) {
                    postRepository.saveSnapshot(
                        Post(binding.postId, imported.title, imported.content),
                        imported.sourceRevision,
                        now,
                    )
                }
                postRepository.recordFirstPublication(binding.postId, now)
                postRepository.saveAvailability(PostAvailability(binding.postId, PostAvailabilityStatus.PUBLISHED, now))
            }

            ImportedPublicationStatus.UNPUBLISHED -> {
                postRepository.saveAvailability(
                    PostAvailability(binding.postId, PostAvailabilityStatus.UNPUBLISHED, now),
                )
            }
        }

        recordSuccess(binding.postId, now)
        return binding.postId
    }

    @Transactional
    fun recordFailure(
        postId: PostId,
        kind: SyncFailureKind,
    ) {
        val now = clock.instant()
        val target = SyncTarget.Post(postId)
        val current = syncStateRepository.find(target)
        val nextFailureCount = Math.addExact(current?.failureCount ?: 0, 1)
        val refreshAfter = refreshPolicy.nextFailureRefreshAt(now, nextFailureCount)
        val updated =
            current?.recordFailure(kind, refreshAfter)
                ?: SyncState(target, null, refreshAfter, nextFailureCount, kind)

        syncStateRepository.save(updated)
    }

    private fun requiresSnapshotRewrite(
        postId: PostId,
        imported: ImportedPost,
    ): Boolean =
        try {
            postRepository.find(postId)?.sourceRevision != imported.sourceRevision
        } catch (failure: SnapshotContentException) {
            snapshotFailures.report(failure, SnapshotFailureOperation.SNAPSHOT_REPAIR, postId)
            true
        }

    private fun recordSuccess(
        postId: PostId,
        now: Instant,
    ) {
        val target = SyncTarget.Post(postId)
        val refreshAfter = refreshPolicy.nextSuccessfulRefreshAt(now)
        val updated =
            syncStateRepository.find(target)?.recordSuccess(now, refreshAfter)
                ?: SyncState(target, now, refreshAfter, 0, null)

        syncStateRepository.save(updated)
    }
}
