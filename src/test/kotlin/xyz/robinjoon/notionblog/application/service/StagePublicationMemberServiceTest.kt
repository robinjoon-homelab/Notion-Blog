package xyz.robinjoon.notionblog.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.publication.BlogPublication
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class StagePublicationMemberServiceTest {
    private val now = Instant.parse("2026-08-25T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val publicationId = PublicationId(UUID.fromString("d2719cb7-bf66-4af3-a007-7fd249c1b414"))
    private val stagingRevisionId = PublicationRevisionId(UUID.fromString("e14a60a4-b2d3-40cc-94c4-c586e8bcc57e"))
    private val replacementRevisionId = PublicationRevisionId(UUID.fromString("89d28507-b74d-4216-8c5c-b031a448116e"))
    private val rootPostId = PostId(UUID.fromString("96bfdb45-7f5a-46fc-bf7e-e3d5495819b8"))

    @Test
    fun `public write methods own transaction boundaries`() {
        assertThat(
            transactionalMethod("begin", UUID::class.java)
                .isAnnotationPresent(Transactional::class.java),
        ).isTrue()
        assertThat(
            transactionalMethod("stage", PublicationMember::class.java)
                .isAnnotationPresent(Transactional::class.java),
        ).isTrue()
        assertThat(
            transactionalMethod("abandon", UUID::class.java, SyncFailureKind::class.java)
                .isAnnotationPresent(Transactional::class.java),
        ).isTrue()
    }

    @Test
    fun `begin abandons stale staging revisions before creating a replacement`() {
        val publicationRepository =
            RecordingPublicationRepository().apply {
                revisions[stagingRevisionId] = revision(stagingRevisionId, PublicationRevisionState.STAGING)
            }
        val service = service(publicationRepository)

        val created = service.begin(publicationId)

        assertThat(created).isEqualTo(revision(replacementRevisionId, PublicationRevisionState.STAGING))
        assertThat(publicationRepository.events).containsExactly(
            "update:$stagingRevisionId:ABANDONED:$now",
            "create:$replacementRevisionId:STAGING:$now",
        )
        assertThat(publicationRepository.revisions.getValue(stagingRevisionId).state)
            .isEqualTo(PublicationRevisionState.ABANDONED)
    }

    @Test
    fun `stage saves a member only for a staging revision`() {
        val publicationRepository =
            RecordingPublicationRepository().apply {
                revisions[stagingRevisionId] = revision(stagingRevisionId, PublicationRevisionState.STAGING)
            }
        val member = PublicationMember(stagingRevisionId, rootPostId, parentPostId = null, depth = 0)

        service(publicationRepository).stage(member)

        assertThat(publicationRepository.members).containsExactly(member)
    }

    @Test
    fun `stage rejects a member for an inactive revision without saving it`() {
        val publicationRepository =
            RecordingPublicationRepository().apply {
                revisions[stagingRevisionId] = revision(stagingRevisionId, PublicationRevisionState.ABANDONED)
            }
        val member = PublicationMember(stagingRevisionId, rootPostId, parentPostId = null, depth = 0)

        assertThatIllegalArgumentException().isThrownBy { service(publicationRepository).stage(member) }

        assertThat(publicationRepository.members).isEmpty()
    }

    @Test
    fun `abandon records failure backoff after changing the staging revision state`() {
        val publicationRepository =
            RecordingPublicationRepository().apply {
                revisions[stagingRevisionId] = revision(stagingRevisionId, PublicationRevisionState.STAGING)
            }
        val syncStateRepository = RecordingSyncStateRepository()
        val service = service(publicationRepository, syncStateRepository)

        service.abandon(stagingRevisionId, SyncFailureKind.RETRYABLE_SOURCE)

        assertThat(publicationRepository.events).containsExactly("update:$stagingRevisionId:ABANDONED:$now")
        assertThat(syncStateRepository.saved).isEqualTo(
            SyncState(
                target = SyncTarget.Publication(publicationId),
                lastSuccessAt = null,
                refreshAfter = now.plusSeconds(30),
                failureCount = 1,
                lastErrorKind = SyncFailureKind.RETRYABLE_SOURCE,
            ),
        )
    }

    private fun service(
        publicationRepository: RecordingPublicationRepository,
        syncStateRepository: RecordingSyncStateRepository = RecordingSyncStateRepository(),
    ) = StagePublicationMemberService(
        publicationRepository = publicationRepository,
        syncStateRepository = syncStateRepository,
        clock = clock,
        refreshPolicy = RefreshPolicy(Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofMinutes(10)),
        revisionIdFactory = { replacementRevisionId },
    )

    private fun revision(
        id: PublicationRevisionId,
        state: PublicationRevisionState,
    ) = PublicationRevision(id, publicationId, state)

    private fun transactionalMethod(
        logicalName: String,
        vararg parameterTypes: Class<*>,
    ) = StagePublicationMemberService::class.java.declaredMethods.single { method ->
        method.name.startsWith(logicalName) && method.parameterTypes.toList() == parameterTypes.toList()
    }

    private class RecordingPublicationRepository : PublicationRepository {
        val revisions = linkedMapOf<PublicationRevisionId, PublicationRevision>()
        val members = mutableListOf<PublicationMember>()
        val events = mutableListOf<String>()

        override fun findCurrent(): BlogPublication? = null

        override fun save(publication: BlogPublication) = Unit

        override fun findRevision(revisionId: PublicationRevisionId): PublicationRevision? = revisions[revisionId]

        override fun findActiveRevision(publicationId: PublicationId): PublicationRevision? =
            revisions.values
                .singleOrNull { it.publicationId == publicationId && it.state == PublicationRevisionState.ACTIVE }

        override fun findStagingRevisions(publicationId: PublicationId): List<PublicationRevision> =
            revisions.values
                .filter { it.publicationId == publicationId && it.state == PublicationRevisionState.STAGING }

        override fun createRevision(
            revision: PublicationRevision,
            transitionedAt: Instant,
        ) {
            revisions[revision.id] = revision
            events += "create:${revision.id}:${revision.state}:$transitionedAt"
        }

        override fun updateRevision(
            revision: PublicationRevision,
            transitionedAt: Instant,
        ) {
            revisions[revision.id] = revision
            events += "update:${revision.id}:${revision.state}:$transitionedAt"
        }

        override fun saveMembers(
            revisionId: PublicationRevisionId,
            members: Collection<PublicationMember>,
        ) {
            require(members.all { it.revisionId == revisionId })
            this.members += members
        }

        override fun findMembers(revisionId: PublicationRevisionId): List<PublicationMember> =
            members
                .filter { it.revisionId == revisionId }

        override fun findActiveMemberPostIds(
            publicationId: PublicationId,
            postIds: Set<PostId>,
        ): Set<PostId> = emptySet()

        override fun findActiveDirectChildren(
            publicationId: PublicationId,
            parentPostId: PostId,
        ): List<PublicationMember> = emptyList()
    }

    private class RecordingSyncStateRepository : SyncStateRepository {
        var saved: SyncState? = null

        override fun delete(target: SyncTarget) {
            saved = saved?.takeUnless { it.target == target }
        }

        override fun findDue(
            now: Instant,
            limit: Int,
        ): List<SyncState> = emptyList()

        override fun find(target: SyncTarget): SyncState? = saved?.takeIf { it.target == target }

        override fun save(state: SyncState) {
            saved = state
        }
    }
}
