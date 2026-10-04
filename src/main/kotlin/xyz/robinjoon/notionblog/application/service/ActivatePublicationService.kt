package xyz.robinjoon.notionblog.application.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.domain.publication.BlogPublication
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.publication.PublicationPolicy
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.domain.sync.RefreshPolicy
import xyz.robinjoon.notionblog.domain.sync.SyncState
import xyz.robinjoon.notionblog.domain.sync.SyncTarget
import java.time.Clock
import java.time.Instant

@Service
class ActivatePublicationService(
    private val publicationRepository: PublicationRepository,
    private val postRepository: PostRepository,
    private val syncStateRepository: SyncStateRepository,
    private val clock: Clock,
    private val refreshPolicy: RefreshPolicy,
) {
    @Transactional
    fun activate(revisionId: PublicationRevisionId) {
        val revision = requireNotNull(publicationRepository.findRevision(revisionId)) {
            "publication revision must exist before it can be activated"
        }
        val publication = requireCurrentPublication(revision.publicationId)
        val members = publicationRepository.findMembers(revision.id)
        val memberPostIds = members.mapTo(linkedSetOf()) { it.postId }
        PublicationPolicy.validateForActivation(
            revision = revision,
            members = members,
            availabilityByPostId = postRepository.findAvailabilities(memberPostIds),
            renderablePostIds = postRepository.findRenderablePostIds(memberPostIds),
        )

        val rootPostId = members.single { it.parentPostId == null }.postId
        val now = clock.instant()
        publicationRepository.findActiveRevision(publication.id)?.let { activeRevision ->
            require(activeRevision.id != revision.id) { "a staging revision cannot already be active" }
            val previousPostIds = publicationRepository.findMembers(activeRevision.id).mapTo(linkedSetOf()) { it.postId }
            (previousPostIds - memberPostIds).forEach { postId ->
                syncStateRepository.delete(SyncTarget.Post(postId))
            }
            publicationRepository.updateRevision(activeRevision.supersede(), now)
        }
        publicationRepository.updateRevision(revision.activate(), now)
        publicationRepository.save(publication.activate(rootPostId, revision.id))
        recordSuccess(revision.publicationId, now)
    }

    private fun requireCurrentPublication(revisionPublicationId: PublicationId): BlogPublication {
        val publication = requireNotNull(publicationRepository.findCurrent()) {
            "a publication must exist before a revision can be activated"
        }
        require(publication.id == revisionPublicationId) {
            "the revision being activated must belong to the current publication"
        }
        return publication
    }

    private fun recordSuccess(publicationId: PublicationId, now: Instant) {
        val target = SyncTarget.Publication(publicationId)
        val current = syncStateRepository.find(target)
            ?: SyncState(target, lastSuccessAt = null, refreshAfter = now, failureCount = 0, lastErrorKind = null)
        syncStateRepository.save(current.recordSuccess(now, refreshPolicy.nextSuccessfulRefreshAt(now)))
    }
}
