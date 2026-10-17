package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.CapturedMedia
import xyz.robinjoon.notionblog.blog.application.model.ImportedRevision
import xyz.robinjoon.notionblog.blog.application.model.PostRevisionSummary
import xyz.robinjoon.notionblog.blog.application.model.RefreshPostResult
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.MediaStoragePort
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.sync.RefreshPolicy
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

class ApplyImportedPostServiceTest {
    private val clock = Clock.fixed(PostFixtures.capturedAt, ZoneOffset.UTC)
    private val posts = mockk<PostRepository>()
    private val mediaStorage = mockk<MediaStoragePort>()
    private val syncStates = mockk<SyncStateRepository>()
    private val recorder = SyncStateRecorder(syncStates, RefreshPolicy(Duration.ofMinutes(5), Duration.ofSeconds(5), Duration.ofMinutes(5)))
    private val newRevisionId = PostFixtures.revisionId(2)
    private val service = ApplyImportedPostService(posts, mediaStorage, recorder, clock) { newRevisionId }
    private val postId = PostFixtures.postId(1)
    private val published = PostFixtures.revisionId(1)
    private val post = PostFixtures.publicPost(postId, published = published)
    private val document = post.source

    @Test
    fun `a post that is no longer managed is not found`() {
        every { posts.lock(postId) } returns null

        assertThat(service.apply(postId, imported("rev-2"), CapturedMedia.empty)).isEqualTo(RefreshPostResult.NotFound)
    }

    @Test
    fun `an import applied after the post was archived is not found`() {
        givenWrites(locked = post.archive())
        every { posts.findRevisionSummaries(setOf(published)) } returns summary("rev-1")

        assertThat(service.apply(postId, imported("rev-2"), CapturedMedia.empty)).isEqualTo(RefreshPostResult.NotFound)
    }

    @Test
    fun `a changed source revision is reported as updated with the new revision id`() {
        givenWrites()
        every { posts.findRevisionSummaries(setOf(published)) } returns summary("rev-1")

        assertThat(service.apply(postId, imported("rev-2"), CapturedMedia.empty)).isEqualTo(RefreshPostResult.Updated(newRevisionId))
    }

    @Test
    fun `a source revision already stored by a concurrent import is unchanged`() {
        givenWrites()
        every { posts.findRevisionSummaries(setOf(published)) } returns summary("rev-2")

        assertThat(service.apply(postId, imported("rev-2"), CapturedMedia.empty)).isEqualTo(RefreshPostResult.Unchanged)
        assertThat(service.apply(postId, null, CapturedMedia.empty)).isEqualTo(RefreshPostResult.Unchanged)
    }

    @Test
    fun `a new revision the repository rejects fails the apply`() {
        givenWrites()
        every { posts.findRevisionSummaries(setOf(published)) } returns summary("rev-1")
        every { posts.saveRevision(any(), any()) } throws IllegalArgumentException("missing copy")

        assertThatIllegalArgumentException().isThrownBy { service.apply(postId, imported("rev-2"), CapturedMedia.empty) }
    }

    // 모든 쓰기는 어떤 인자든 받으므로, 테스트는 누락된 스텁이 아니라 반환 결과나 던진 예외로만 판정된다.
    private fun givenWrites(locked: Post = post) {
        every { posts.lock(postId) } returns locked
        every { mediaStorage.save(any(), any()) } just Runs
        every { posts.bindMissingMedia(any(), any()) } just Runs
        every { posts.saveRevision(any(), any()) } just Runs
        every { posts.save(any(), any()) } just Runs
        every { syncStates.find(any()) } returns null
        every { syncStates.save(any()) } just Runs
    }

    private fun summary(sourceRevision: String) =
        mapOf(published to PostRevisionSummary(published, "Title", SourceRevision(sourceRevision), PostFixtures.capturedAt))

    private fun imported(sourceRevision: String) =
        ImportedRevision(document, PostFixtures.properties(), SourceRevision(sourceRevision), PostFixtures.paragraphs("New"))
}
