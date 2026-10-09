package xyz.robinjoon.notionblog.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.application.model.PostLookupResult
import xyz.robinjoon.notionblog.application.model.StoredPost
import xyz.robinjoon.notionblog.application.port.output.diagnostics.SnapshotFailureOperation
import xyz.robinjoon.notionblog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.publication.BlogPublication
import xyz.robinjoon.notionblog.domain.publication.PostAvailability
import xyz.robinjoon.notionblog.domain.publication.PostAvailabilityStatus
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.domain.source.SourceRevision
import java.time.Instant
import java.util.UUID

class GetPublishedPostServiceTest {
    private val snapshotFailures = mockk<SnapshotFailureReporter>(relaxed = true)
    private val publicationRepository = mockk<PublicationRepository>()
    private val postRepository = mockk<PostRepository>()
    private val service = GetPublishedPostService(publicationRepository, postRepository, snapshotFailures)

    @Test
    fun `returns content unavailable when no active publication exists`() {
        every { publicationRepository.findCurrent() } returns null

        assertThat(service.get(postId())).isEqualTo(PostLookupResult.ContentUnavailable)
    }

    @Test
    fun `does not query members when publication has no active revision`() {
        val publication = BlogPublication(PublicationId(UUID.randomUUID()), null, null)
        every { publicationRepository.findCurrent() } returns publication

        assertThat(service.get(postId())).isEqualTo(PostLookupResult.ContentUnavailable)
        assertThat(service.getRoot()).isEqualTo(PostLookupResult.ContentUnavailable)
        verify(exactly = 0) { publicationRepository.findActiveMemberPostIds(any(), any()) }
    }

    @Test
    fun `returns not found when requested post is outside the active publication`() {
        val publication = activePublication()
        val postId = postId()
        every { publicationRepository.findCurrent() } returns publication
        every { publicationRepository.findActiveMemberPostIds(publication.id, setOf(postId)) } returns emptySet()

        assertThat(service.get(postId)).isEqualTo(PostLookupResult.NotFound)
    }

    @Test
    fun `returns not found when active member is unpublished`() {
        val publication = activePublication()
        val postId = postId()
        every { publicationRepository.findCurrent() } returns publication
        every { publicationRepository.findActiveMemberPostIds(publication.id, setOf(postId)) } returns setOf(postId)
        every { postRepository.findAvailability(postId) } returns availability(postId, PostAvailabilityStatus.UNPUBLISHED)

        assertThat(service.get(postId)).isEqualTo(PostLookupResult.NotFound)
    }

    @Test
    fun `returns content unavailable when active member has no availability record`() {
        val publication = activePublication()
        val postId = postId()
        every { publicationRepository.findCurrent() } returns publication
        every { publicationRepository.findActiveMemberPostIds(publication.id, setOf(postId)) } returns setOf(postId)
        every { postRepository.findAvailability(postId) } returns null

        assertThat(service.get(postId)).isEqualTo(PostLookupResult.ContentUnavailable)
    }

    @Test
    fun `returns content unavailable when published member has no snapshot`() {
        val publication = activePublication()
        val postId = postId()
        every { publicationRepository.findCurrent() } returns publication
        every { publicationRepository.findActiveMemberPostIds(publication.id, setOf(postId)) } returns setOf(postId)
        every { postRepository.findAvailability(postId) } returns availability(postId, PostAvailabilityStatus.PUBLISHED)
        every { postRepository.find(postId) } returns null

        assertThat(service.get(postId)).isEqualTo(PostLookupResult.ContentUnavailable)
        verify(exactly = 0) { snapshotFailures.report(any(), any(), any()) }
    }

    @Test
    fun `reports corrupt post snapshot with its identity and returns content unavailable`() {
        val publication = activePublication()
        val postId = postId()
        every { publicationRepository.findCurrent() } returns publication
        every { publicationRepository.findActiveMemberPostIds(publication.id, setOf(postId)) } returns setOf(postId)
        every { postRepository.findAvailability(postId) } returns availability(postId, PostAvailabilityStatus.PUBLISHED)
        val failure = SnapshotContentException("invalid snapshot", IllegalArgumentException("invalid content"))
        val reported = slot<SnapshotContentException>()
        every { postRepository.find(postId) } throws failure

        assertThat(service.get(postId)).isEqualTo(PostLookupResult.ContentUnavailable)
        verify(exactly = 1) { snapshotFailures.report(capture(reported), SnapshotFailureOperation.POST_LOOKUP, postId) }
        assertThat(reported.captured).isSameAs(failure)
    }

    @Test
    fun `returns stored post only after active membership availability and snapshot gates pass`() {
        val publication = activePublication()
        val postId = postId()
        val post = Post(postId, "Published", BlockTree(emptyList()))
        every { publicationRepository.findCurrent() } returns publication
        every { publicationRepository.findActiveMemberPostIds(publication.id, setOf(postId)) } returns setOf(postId)
        every { postRepository.findAvailability(postId) } returns availability(postId, PostAvailabilityStatus.PUBLISHED)
        every { postRepository.find(postId) } returns StoredPost(post, SourceRevision("revision"), Instant.EPOCH)

        assertThat(service.get(postId)).isEqualTo(PostLookupResult.Found(post))
    }

    @Test
    fun `returns content unavailable for root when publication has no active root`() {
        every { publicationRepository.findCurrent() } returns BlogPublication(PublicationId(UUID.randomUUID()), null, null)

        assertThat(service.getRoot()).isEqualTo(PostLookupResult.ContentUnavailable)
    }

    private fun activePublication(rootPostId: PostId = postId()): BlogPublication =
        BlogPublication(
            id = PublicationId(UUID.randomUUID()),
            rootPostId = rootPostId,
            activeRevisionId = PublicationRevisionId(UUID.randomUUID()),
        )

    private fun availability(
        postId: PostId,
        status: PostAvailabilityStatus,
    ): PostAvailability = PostAvailability(postId, status, Instant.EPOCH)

    private fun postId(): PostId = PostId(UUID.randomUUID())
}
