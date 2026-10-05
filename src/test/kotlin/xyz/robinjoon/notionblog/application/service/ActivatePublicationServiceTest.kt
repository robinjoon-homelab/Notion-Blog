package xyz.robinjoon.notionblog.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.application.model.PostFeedEntry
import xyz.robinjoon.notionblog.application.model.StoredPost
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.publication.BlogPublication
import xyz.robinjoon.notionblog.domain.publication.PostAvailability
import xyz.robinjoon.notionblog.domain.publication.PostAvailabilityStatus
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.publication.PublicationMember
import xyz.robinjoon.notionblog.domain.publication.PublicationRevision
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionState
import xyz.robinjoon.notionblog.domain.source.PostSourceBinding
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceRevision
import xyz.robinjoon.notionblog.domain.sync.RefreshPolicy
import xyz.robinjoon.notionblog.domain.sync.SyncState
import xyz.robinjoon.notionblog.domain.sync.SyncTarget
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ActivatePublicationServiceTest {
    private val now = Instant.parse("2026-08-25T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val publicationId = PublicationId(UUID.fromString("d2719cb7-bf66-4af3-a007-7fd249c1b414"))
    private val previousRevisionId = PublicationRevisionId(UUID.fromString("e14a60a4-b2d3-40cc-94c4-c586e8bcc57e"))
    private val stagingRevisionId = PublicationRevisionId(UUID.fromString("89d28507-b74d-4216-8c5c-b031a448116e"))
    private val rootPostId = PostId(UUID.fromString("96bfdb45-7f5a-46fc-bf7e-e3d5495819b8"))
    private val unpublishedPostId = PostId(UUID.fromString("a6f4fd8b-05c5-4e67-a6ce-e2a05d885b93"))

    @Test
    fun `activation method owns its transaction boundary`() {
        assertThat(
            ActivatePublicationService::class.java
                .declaredMethods
                .single { method -> method.name.startsWith("activate") && method.parameterTypes.toList() == listOf(UUID::class.java) }
                .isAnnotationPresent(Transactional::class.java),
        ).isTrue()
    }

    @Test
    fun `activation supersedes old revision before activating a complete staging graph and preserves unpublished members`() {
        val publicationRepository = RecordingPublicationRepository(
            publication = BlogPublication(publicationId, rootPostId, previousRevisionId),
            revisions = listOf(
                PublicationRevision(previousRevisionId, publicationId, PublicationRevisionState.ACTIVE),
                PublicationRevision(stagingRevisionId, publicationId, PublicationRevisionState.STAGING),
            ),
            members = listOf(
                PublicationMember(stagingRevisionId, rootPostId, parentPostId = null, depth = 0),
                PublicationMember(stagingRevisionId, unpublishedPostId, parentPostId = rootPostId, depth = 1),
            ),
        )
        val postRepository = RecordingPostRepository(
            availabilities = mapOf(
                rootPostId to published(rootPostId),
                unpublishedPostId to unpublished(unpublishedPostId),
            ),
            renderablePostIds = setOf(rootPostId),
        )
        val syncStateRepository = RecordingSyncStateRepository()

        service(publicationRepository, postRepository, syncStateRepository).activate(stagingRevisionId)

        assertThat(publicationRepository.events).containsExactly(
            "update:$previousRevisionId:SUPERSEDED",
            "update:$stagingRevisionId:ACTIVE",
            "save-publication:$rootPostId:$stagingRevisionId",
        )
        assertThat(publicationRepository.revisions.getValue(previousRevisionId).state).isEqualTo(PublicationRevisionState.SUPERSEDED)
        assertThat(publicationRepository.revisions.getValue(stagingRevisionId).state).isEqualTo(PublicationRevisionState.ACTIVE)
        assertThat(publicationRepository.publication).isEqualTo(BlogPublication(publicationId, rootPostId, stagingRevisionId))
        assertThat(syncStateRepository.saved).isEqualTo(
            SyncState(SyncTarget.Publication(publicationId), now, now.plusSeconds(600), 0, null),
        )
    }

    @Test
    fun `activation removes only the reservation of a member leaving the publication`() {
        val removedPostId = PostId(UUID.randomUUID())
        val publicationRepository = RecordingPublicationRepository(
            publication = BlogPublication(publicationId, rootPostId, previousRevisionId),
            revisions = listOf(
                PublicationRevision(previousRevisionId, publicationId, PublicationRevisionState.ACTIVE),
                PublicationRevision(stagingRevisionId, publicationId, PublicationRevisionState.STAGING),
            ),
            members = listOf(
                PublicationMember(previousRevisionId, rootPostId, null, 0),
                PublicationMember(previousRevisionId, unpublishedPostId, rootPostId, 1),
                PublicationMember(previousRevisionId, removedPostId, rootPostId, 1),
                PublicationMember(stagingRevisionId, rootPostId, null, 0),
                PublicationMember(stagingRevisionId, unpublishedPostId, rootPostId, 1),
            ),
        )
        val postRepository = RecordingPostRepository(
            mapOf(rootPostId to published(rootPostId), unpublishedPostId to unpublished(unpublishedPostId)),
            setOf(rootPostId),
        )
        val syncStateRepository = RecordingSyncStateRepository()
        val removedState = SyncState(SyncTarget.Post(removedPostId), now, now.plusSeconds(3_600), 0, null)
        val retainedStates = listOf(rootPostId, unpublishedPostId).map {
            SyncState(SyncTarget.Post(it), now, now.plusSeconds(600), 0, null)
        }
        (retainedStates + removedState).forEach(syncStateRepository::save)

        service(publicationRepository, postRepository, syncStateRepository).activate(stagingRevisionId)

        assertThat(syncStateRepository.find(removedState.target)).isNull()
        retainedStates.forEach { assertThat(syncStateRepository.find(it.target)).isEqualTo(it) }
    }

    @Test
    fun `activation rejects published members without snapshots before changing any state`() {
        val publicationRepository = RecordingPublicationRepository(
            publication = BlogPublication(publicationId, rootPostId, previousRevisionId),
            revisions = listOf(
                PublicationRevision(previousRevisionId, publicationId, PublicationRevisionState.ACTIVE),
                PublicationRevision(stagingRevisionId, publicationId, PublicationRevisionState.STAGING),
            ),
            members = listOf(PublicationMember(stagingRevisionId, rootPostId, parentPostId = null, depth = 0)),
        )
        val postRepository = RecordingPostRepository(mapOf(rootPostId to published(rootPostId)), emptySet())
        val syncStateRepository = RecordingSyncStateRepository()

        assertThatIllegalArgumentException().isThrownBy {
            service(publicationRepository, postRepository, syncStateRepository).activate(stagingRevisionId)
        }

        assertThat(publicationRepository.events).isEmpty()
        assertThat(publicationRepository.revisions.getValue(previousRevisionId).state).isEqualTo(PublicationRevisionState.ACTIVE)
        assertThat(publicationRepository.revisions.getValue(stagingRevisionId).state).isEqualTo(PublicationRevisionState.STAGING)
        assertThat(syncStateRepository.saved).isNull()
    }

    @Test
    fun `activation rejects members without a confirmed availability before changing any state`() {
        val publicationRepository = RecordingPublicationRepository(
            publication = BlogPublication(publicationId, rootPostId, previousRevisionId),
            revisions = listOf(
                PublicationRevision(previousRevisionId, publicationId, PublicationRevisionState.ACTIVE),
                PublicationRevision(stagingRevisionId, publicationId, PublicationRevisionState.STAGING),
            ),
            members = listOf(PublicationMember(stagingRevisionId, rootPostId, parentPostId = null, depth = 0)),
        )
        val syncStateRepository = RecordingSyncStateRepository()

        assertThatIllegalArgumentException().isThrownBy {
            service(
                publicationRepository,
                RecordingPostRepository(emptyMap(), emptySet()),
                syncStateRepository,
            ).activate(stagingRevisionId)
        }

        assertThat(publicationRepository.events).isEmpty()
        assertThat(syncStateRepository.saved).isNull()
    }

    @Test
    fun `activation rejects a disconnected graph before changing any state`() {
        val publicationRepository = RecordingPublicationRepository(
            publication = BlogPublication(publicationId, rootPostId, previousRevisionId),
            revisions = listOf(
                PublicationRevision(previousRevisionId, publicationId, PublicationRevisionState.ACTIVE),
                PublicationRevision(stagingRevisionId, publicationId, PublicationRevisionState.STAGING),
            ),
            members = listOf(
                PublicationMember(stagingRevisionId, rootPostId, parentPostId = null, depth = 0),
                PublicationMember(stagingRevisionId, unpublishedPostId, parentPostId = rootPostId, depth = 2),
            ),
        )
        val postRepository = RecordingPostRepository(
            mapOf(rootPostId to published(rootPostId), unpublishedPostId to unpublished(unpublishedPostId)),
            setOf(rootPostId),
        )

        assertThatIllegalArgumentException().isThrownBy {
            service(publicationRepository, postRepository, RecordingSyncStateRepository()).activate(stagingRevisionId)
        }

        assertThat(publicationRepository.events).isEmpty()
    }

    private fun service(
        publicationRepository: RecordingPublicationRepository,
        postRepository: RecordingPostRepository,
        syncStateRepository: RecordingSyncStateRepository,
    ) = ActivatePublicationService(
        publicationRepository = publicationRepository,
        postRepository = postRepository,
        syncStateRepository = syncStateRepository,
        clock = clock,
        refreshPolicy = RefreshPolicy(Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofMinutes(10)),
    )

    private fun published(postId: PostId) = PostAvailability(postId, PostAvailabilityStatus.PUBLISHED, now)

    private fun unpublished(postId: PostId) = PostAvailability(postId, PostAvailabilityStatus.UNPUBLISHED, now)

    private class RecordingPublicationRepository(
        var publication: BlogPublication,
        revisions: List<PublicationRevision>,
        members: List<PublicationMember>,
    ) : PublicationRepository {
        val revisions = revisions.associateByTo(linkedMapOf(), PublicationRevision::id)
        private val members = members.toList()
        val events = mutableListOf<String>()

        override fun findCurrent(): BlogPublication = publication

        override fun save(publication: BlogPublication) {
            this.publication = publication
            events += "save-publication:${publication.rootPostId}:${publication.activeRevisionId}"
        }

        override fun findRevision(revisionId: PublicationRevisionId): PublicationRevision? = revisions[revisionId]

        override fun findActiveRevision(publicationId: PublicationId): PublicationRevision? = revisions.values
            .singleOrNull { it.publicationId == publicationId && it.state == PublicationRevisionState.ACTIVE }

        override fun findStagingRevisions(publicationId: PublicationId): List<PublicationRevision> = revisions.values
            .filter { it.publicationId == publicationId && it.state == PublicationRevisionState.STAGING }

        override fun createRevision(revision: PublicationRevision, transitionedAt: Instant) = Unit

        override fun updateRevision(revision: PublicationRevision, transitionedAt: Instant) {
            revisions[revision.id] = revision
            events += "update:${revision.id}:${revision.state}"
        }

        override fun saveMembers(revisionId: PublicationRevisionId, members: Collection<PublicationMember>) = Unit

        override fun findMembers(revisionId: PublicationRevisionId): List<PublicationMember> = members
            .filter { it.revisionId == revisionId }

        override fun findActiveMemberPostIds(publicationId: PublicationId, postIds: Set<PostId>): Set<PostId> = emptySet()

        override fun findActiveDirectChildren(publicationId: PublicationId, parentPostId: PostId): List<PublicationMember> = emptyList()
    }

    private class RecordingPostRepository(
        private val availabilities: Map<PostId, PostAvailability>,
        private val renderablePostIds: Set<PostId>,
    ) : PostRepository {
        override fun find(postId: PostId): StoredPost? = null

        override fun findBinding(postId: PostId): PostSourceBinding? = null

        override fun findBinding(sourceDocument: SourceDocumentRef): PostSourceBinding? = null

        override fun findBindingsBySourceDocuments(sourceDocuments: Set<SourceDocumentRef>): Map<SourceDocumentRef, PostSourceBinding> = emptyMap()

        override fun findBindingsByPostIds(postIds: Set<PostId>): Map<PostId, PostSourceBinding> = emptyMap()

        override fun saveIdentity(binding: PostSourceBinding, title: String, changedAt: Instant) = Unit

        override fun saveSnapshot(post: Post, sourceRevision: SourceRevision, capturedAt: Instant) = Unit

        override fun recordFirstPublication(postId: PostId, observedAt: Instant) {
            error("publication activation does not record first publication times")
        }

        override fun findRecentPublishedPosts(
            publicationId: PublicationId,
            excludedPostIds: Set<PostId>,
            limit: Int,
        ): List<PostFeedEntry> = error("publication activation does not read the post feed")

        override fun findAvailability(postId: PostId): PostAvailability? = availabilities[postId]

        override fun findAvailabilities(postIds: Set<PostId>): Map<PostId, PostAvailability> = availabilities.filterKeys { it in postIds }

        override fun saveAvailability(availability: PostAvailability) = Unit

        override fun saveAvailabilities(availabilities: Collection<PostAvailability>) = Unit

        override fun findRenderablePostIds(postIds: Set<PostId>): Set<PostId> = renderablePostIds.intersect(postIds)
    }

    private class RecordingSyncStateRepository : SyncStateRepository {
        var saved: SyncState? = null
        private val states = mutableMapOf<SyncTarget, SyncState>()

        override fun findDue(now: Instant, limit: Int): List<SyncState> = emptyList()

        override fun find(target: SyncTarget): SyncState? = states[target]

        override fun save(state: SyncState) {
            saved = state
            states[state.target] = state
        }

        override fun delete(target: SyncTarget) {
            states.remove(target)
        }
    }
}
