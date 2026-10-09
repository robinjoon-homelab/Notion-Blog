package xyz.robinjoon.notionblog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.publication.PublicationMember
import xyz.robinjoon.notionblog.domain.publication.PublicationRevision
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionState
import xyz.robinjoon.notionblog.domain.sync.RefreshPolicy
import xyz.robinjoon.notionblog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.domain.sync.SyncState
import xyz.robinjoon.notionblog.domain.sync.SyncTarget
import java.time.Clock
import java.util.UUID

@Transactional
class StagePublicationMemberService(
    private val publicationRepository: PublicationRepository,
    private val syncStateRepository: SyncStateRepository,
    private val clock: Clock,
    private val refreshPolicy: RefreshPolicy,
    private val revisionIdFactory: () -> PublicationRevisionId = { PublicationRevisionId(UUID.randomUUID()) },
) {
    @Transactional
    fun begin(publicationId: PublicationId): PublicationRevision {
        val now = clock.instant()
        publicationRepository.findStagingRevisions(publicationId).forEach { stagingRevision ->
            publicationRepository.updateRevision(stagingRevision.abandon(), now)
        }
        return PublicationRevision(revisionIdFactory(), publicationId, PublicationRevisionState.STAGING).also { revision ->
            publicationRepository.createRevision(revision, now)
        }
    }

    @Transactional
    fun stage(member: PublicationMember) {
        val revision =
            requireNotNull(publicationRepository.findRevision(member.revisionId)) {
                "publication revision must exist before a member can be staged"
            }
        require(revision.state == PublicationRevisionState.STAGING) {
            "publication members can only be staged on a staging revision"
        }
        publicationRepository.saveMembers(member.revisionId, listOf(member))
    }

    @Transactional
    fun abandon(
        revisionId: PublicationRevisionId,
        failureKind: SyncFailureKind,
    ) {
        val revision =
            requireNotNull(publicationRepository.findRevision(revisionId)) {
                "publication revision must exist before it can be abandoned"
            }
        val now = clock.instant()
        publicationRepository.updateRevision(revision.abandon(), now)

        val target = SyncTarget.Publication(revision.publicationId)
        val current =
            syncStateRepository.find(target)
                ?: SyncState(target, lastSuccessAt = null, refreshAfter = now, failureCount = 0, lastErrorKind = null)
        val failureCount = current.failureCount + 1
        syncStateRepository.save(
            current.recordFailure(failureKind, refreshPolicy.nextFailureRefreshAt(now, failureCount)),
        )
    }
}
