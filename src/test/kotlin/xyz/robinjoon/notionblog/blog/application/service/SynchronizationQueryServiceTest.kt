package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.MediaRepairTarget
import xyz.robinjoon.notionblog.blog.application.model.PostRevisionSummary
import xyz.robinjoon.notionblog.blog.application.model.PostSynchronizationContext
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.sync.SyncState
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.time.Instant

class SynchronizationQueryServiceTest {
    private val posts = mockk<PostRepository>()
    private val syncStates = mockk<SyncStateRepository>()
    private val service = SynchronizationQueryService(posts, syncStates)
    private val postId = PostFixtures.postId(1)

    @Test
    fun `unmanaged and archived posts are not synchronized`() {
        every { posts.find(postId) } returns null

        assertThat(service.loadPost(postId)).isNull()

        every { posts.find(postId) } returns PostFixtures.privatePost(postId).archive()

        assertThat(service.loadPost(postId)).isNull()
    }

    @Test
    fun `the context carries the latest source revision and missing legacy copies of shown revisions`() {
        val published = PostFixtures.revisionId(1)
        val latest = PostFixtures.revisionId(2)
        val post = PostFixtures.publicPost(postId, published = published, latest = latest)
        val legacyFile = PostFixtures.hosted("legacy.png")
        every { posts.find(postId) } returns post
        every { posts.findUnboundMedia(setOf(latest, published)) } returns mapOf(published to setOf(legacyFile))

        every { posts.findRevisionSummaries(setOf(latest, published)) } returns
            mapOf(
                latest to PostRevisionSummary(latest, "Title", SourceRevision("rev-2"), PostFixtures.capturedAt),
                published to PostRevisionSummary(published, "Title", SourceRevision("legacy"), PostFixtures.capturedAt),
            )

        assertThat(service.loadPost(postId)).isEqualTo(
            PostSynchronizationContext(
                postId,
                post.source,
                SourceRevision("rev-2"),
                listOf(MediaRepairTarget(published, SourceRevision("legacy"), setOf(legacyFile))),
            ),
        )
    }

    @Test
    fun `due targets keep the order of the stored schedules`() {
        val now = Instant.parse("2026-10-16T01:00:00Z")
        val first = SyncTarget.Post(PostFixtures.postId(2))
        val second = SyncTarget.SiteConfiguration
        val third = SyncTarget.Post(PostFixtures.postId(1))
        every { syncStates.findDue(now, 3) } returns
            listOf(first, second, third).map { SyncState.schedule(it, now.minusSeconds(60)) }

        assertThat(service.findDueTargets(now, 3)).containsExactly(first, second, third)
    }

    @Test
    fun `due targets reject limits that are not positive`() {
        val now = Instant.parse("2026-10-16T01:00:00Z")

        assertThatIllegalArgumentException().isThrownBy { service.findDueTargets(now, 0) }
        assertThatIllegalArgumentException().isThrownBy { service.findDueTargets(now, -1) }
    }
}
