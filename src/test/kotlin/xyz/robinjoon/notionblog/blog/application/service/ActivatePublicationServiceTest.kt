package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.domain.post.PostAvailability
import xyz.robinjoon.notionblog.blog.domain.post.PostAvailabilityStatus
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.publication.BlogPublication
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationMember
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationRevision
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationRevisionState
import xyz.robinjoon.notionblog.blog.domain.sync.RefreshPolicy
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
    private val stagingRevision = PublicationRevision(stagingRevisionId, publicationId, PublicationRevisionState.STAGING)

    private val publicationRepository = mockk<PublicationRepository>()
    private val postRepository = mockk<PostRepository>()
    private val syncStateRepository = mockk<SyncStateRepository>()
    private val service =
        ActivatePublicationService(
            publicationRepository = publicationRepository,
            postRepository = postRepository,
            syncStateRepository = syncStateRepository,
            clock = clock,
            refreshPolicy = RefreshPolicy(Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofMinutes(10)),
        )

    @Test
    fun `activation rejects published members without snapshots`() {
        givenStagingGraph(
            listOf(PublicationMember(stagingRevisionId, rootPostId, parentPostId = null, depth = 0)),
            availabilities = mapOf(rootPostId to published(rootPostId)),
            renderablePostIds = emptySet(),
        )

        assertThatIllegalArgumentException().isThrownBy { service.activate(stagingRevisionId) }
    }

    @Test
    fun `activation rejects members without a confirmed availability`() {
        givenStagingGraph(
            listOf(PublicationMember(stagingRevisionId, rootPostId, parentPostId = null, depth = 0)),
            availabilities = emptyMap(),
            renderablePostIds = emptySet(),
        )

        assertThatIllegalArgumentException().isThrownBy { service.activate(stagingRevisionId) }
    }

    @Test
    fun `activation rejects a disconnected graph`() {
        givenStagingGraph(
            listOf(
                PublicationMember(stagingRevisionId, rootPostId, parentPostId = null, depth = 0),
                PublicationMember(stagingRevisionId, unpublishedPostId, parentPostId = rootPostId, depth = 2),
            ),
            availabilities = mapOf(rootPostId to published(rootPostId), unpublishedPostId to unpublished(unpublishedPostId)),
            renderablePostIds = setOf(rootPostId),
        )

        assertThatIllegalArgumentException().isThrownBy { service.activate(stagingRevisionId) }
    }

    private fun givenStagingGraph(
        members: List<PublicationMember>,
        availabilities: Map<PostId, PostAvailability>,
        renderablePostIds: Set<PostId>,
    ) {
        val memberPostIds = members.mapTo(linkedSetOf()) { it.postId }
        every { publicationRepository.findRevision(stagingRevisionId) } returns stagingRevision
        every { publicationRepository.findCurrent() } returns BlogPublication.restore(publicationId, rootPostId, previousRevisionId)
        every { publicationRepository.findMembers(stagingRevisionId) } returns members
        every { postRepository.findAvailabilities(memberPostIds) } returns availabilities
        every { postRepository.findRenderablePostIds(memberPostIds) } returns renderablePostIds
    }

    private fun published(postId: PostId) = PostAvailability(postId, PostAvailabilityStatus.PUBLISHED, now)

    private fun unpublished(postId: PostId) = PostAvailability(postId, PostAvailabilityStatus.UNPUBLISHED, now)
}
