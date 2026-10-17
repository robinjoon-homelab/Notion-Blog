package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.ArchivePostResult
import xyz.robinjoon.notionblog.blog.application.model.PublishPostCommand
import xyz.robinjoon.notionblog.blog.application.model.PublishPostResult
import xyz.robinjoon.notionblog.blog.application.model.RestorePostResult
import xyz.robinjoon.notionblog.blog.application.model.UnpublishPostResult
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.domain.sync.RefreshPolicy
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

class PublishPostServiceTest {
    private val clock = Clock.fixed(PostFixtures.capturedAt, ZoneOffset.UTC)
    private val posts = mockk<PostRepository>()
    private val syncStates = mockk<SyncStateRepository>()
    private val recorder = SyncStateRecorder(syncStates, RefreshPolicy(Duration.ofMinutes(5), Duration.ofSeconds(5), Duration.ofMinutes(5)))
    private val service = PublishPostService(posts, recorder, clock)
    private val postId = PostFixtures.postId(1)
    private val first = PostFixtures.revisionId(1)
    private val second = PostFixtures.revisionId(2)

    @BeforeEach
    fun givenWrites() {
        every { posts.save(any(), any()) } just Runs
        every { posts.findUnboundMedia(any()) } returns emptyMap()
        every { syncStates.find(any()) } returns null
        every { syncStates.save(any()) } just Runs
        every { syncStates.delete(any()) } just Runs
    }

    @Test
    fun `publishes the reviewed latest revision of the shown generation`() {
        every { posts.lock(postId) } returns PostFixtures.privatePost(postId, latest = first, generation = 3)

        assertThat(service.publish(PublishPostCommand(postId, first, 3))).isEqualTo(PublishPostResult.Published)
    }

    @Test
    fun `a publish prepared before a later admin command is rejected`() {
        // 세대 3에서 게시되었다가 게시 취소되어, 지금은 세대 5의 비공개 글이다.
        every { posts.lock(postId) } returns PostFixtures.privatePost(postId, latest = first, generation = 5)

        assertThat(service.publish(PublishPostCommand(postId, first, 3))).isEqualTo(PublishPostResult.StaleRevision)
    }

    @Test
    fun `a publish of a revision that is no longer the latest is rejected`() {
        every { posts.lock(postId) } returns PostFixtures.privatePost(postId, latest = second, generation = 3)

        assertThat(service.publish(PublishPostCommand(postId, first, 3))).isEqualTo(PublishPostResult.StaleRevision)
    }

    @Test
    fun `publishing the revision readers already get is idempotent`() {
        every { posts.lock(postId) } returns PostFixtures.publicPost(postId, published = first, generation = 4)

        assertThat(service.publish(PublishPostCommand(postId, first, 3))).isEqualTo(PublishPostResult.AlreadyPublished)
    }

    @Test
    fun `a revision with a file that has no stored copy cannot be published`() {
        every { posts.lock(postId) } returns PostFixtures.privatePost(postId, latest = first, generation = 3)
        every { posts.findUnboundMedia(setOf(first)) } returns mapOf(first to setOf(PostFixtures.hosted("legacy.png")))

        assertThat(service.publish(PublishPostCommand(postId, first, 3))).isEqualTo(PublishPostResult.MediaNotReady)
    }

    @Test
    fun `archived and unknown posts cannot be published`() {
        every { posts.lock(postId) } returns PostFixtures.privatePost(postId, latest = first, generation = 3).archive()

        assertThat(service.publish(PublishPostCommand(postId, first, 4))).isEqualTo(PublishPostResult.Archived)

        every { posts.lock(postId) } returns null

        assertThat(service.publish(PublishPostCommand(postId, first, 3))).isEqualTo(PublishPostResult.NotFound)
    }

    @Test
    fun `unpublishing needs no expected state and reports whether readers lost access`() {
        every { posts.lock(postId) } returns PostFixtures.publicPost(postId, published = first)

        assertThat(service.unpublish(postId)).isEqualTo(UnpublishPostResult.Unpublished)

        every { posts.lock(postId) } returns PostFixtures.privatePost(postId, latest = first)

        assertThat(service.unpublish(postId)).isEqualTo(UnpublishPostResult.AlreadyUnpublished)

        every { posts.lock(postId) } returns null

        assertThat(service.unpublish(postId)).isEqualTo(UnpublishPostResult.NotFound)
    }

    @Test
    fun `archive and restore report the state they found`() {
        val public = PostFixtures.publicPost(postId, published = first)
        every { posts.lock(postId) } returns public

        assertThat(service.archive(postId)).isEqualTo(ArchivePostResult.Archived)
        assertThat(service.restore(postId)).isEqualTo(RestorePostResult.NotArchived)

        every { posts.lock(postId) } returns public.archive()

        assertThat(service.archive(postId)).isEqualTo(ArchivePostResult.AlreadyArchived)
        assertThat(service.restore(postId)).isEqualTo(RestorePostResult.Restored)
    }
}
