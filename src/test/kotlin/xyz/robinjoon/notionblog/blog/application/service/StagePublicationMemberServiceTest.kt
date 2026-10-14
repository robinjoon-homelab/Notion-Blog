package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.domain.post.PostId
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

class StagePublicationMemberServiceTest {
    private val now = Instant.parse("2026-08-25T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val publicationId = PublicationId(UUID.fromString("d2719cb7-bf66-4af3-a007-7fd249c1b414"))
    private val stagingRevisionId = PublicationRevisionId(UUID.fromString("e14a60a4-b2d3-40cc-94c4-c586e8bcc57e"))
    private val replacementRevisionId = PublicationRevisionId(UUID.fromString("89d28507-b74d-4216-8c5c-b031a448116e"))
    private val rootPostId = PostId(UUID.fromString("96bfdb45-7f5a-46fc-bf7e-e3d5495819b8"))
    private val publicationRepository = mockk<PublicationRepository>()
    private val syncStateRepository = mockk<SyncStateRepository>()
    private val service =
        StagePublicationMemberService(
            publicationRepository = publicationRepository,
            syncStateRepository = syncStateRepository,
            clock = clock,
            refreshPolicy = RefreshPolicy(Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofMinutes(10)),
            revisionIdFactory = { replacementRevisionId },
        )

    @Test
    fun `begin returns a replacement staging revision while stale staging revisions exist`() {
        every { publicationRepository.findStagingRevisions(publicationId) } returns
            listOf(revision(stagingRevisionId, PublicationRevisionState.STAGING))
        every { publicationRepository.updateRevision(any(), now) } just Runs
        every { publicationRepository.createRevision(any(), now) } just Runs

        val created = service.begin(publicationId)

        assertThat(created).isEqualTo(revision(replacementRevisionId, PublicationRevisionState.STAGING))
    }

    @Test
    fun `stage rejects a member for an inactive revision`() {
        val member = PublicationMember(stagingRevisionId, rootPostId, parentPostId = null, depth = 0)
        every { publicationRepository.findRevision(stagingRevisionId) } returns
            revision(stagingRevisionId, PublicationRevisionState.ABANDONED)

        assertThatIllegalArgumentException().isThrownBy { service.stage(member) }
    }

    private fun revision(
        id: PublicationRevisionId,
        state: PublicationRevisionState,
    ) = PublicationRevision(id, publicationId, state)
}
