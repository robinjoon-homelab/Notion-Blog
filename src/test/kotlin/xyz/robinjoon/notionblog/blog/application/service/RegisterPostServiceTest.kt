package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.CatalogEntry
import xyz.robinjoon.notionblog.blog.application.model.CatalogSynchronization
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.blog.domain.sync.RefreshPolicy
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

class RegisterPostServiceTest {
    private val posts = mockk<PostRepository>()
    private val syncStates = mockk<SyncStateRepository>()
    private val recorder = SyncStateRecorder(syncStates, RefreshPolicy(Duration.ofMinutes(5), Duration.ofSeconds(5), Duration.ofMinutes(5)))
    private val generatedId = PostFixtures.postId(9)
    private val service = RegisterPostService(posts, recorder, Clock.fixed(PostFixtures.capturedAt, ZoneOffset.UTC)) { generatedId }

    @BeforeEach
    fun givenWrites() {
        every { posts.register(any(), any(), any()) } just Runs
        every { syncStates.find(any()) } returns null
        every { syncStates.save(any()) } just Runs
    }

    @Test
    fun `a scan registers new and unmanaged legacy documents once and leaves managed posts alone`() {
        val managed = PostFixtures.document("managed")
        val legacy = PostFixtures.document("legacy")
        val fresh = PostFixtures.document("fresh")
        every { posts.findBinding(managed) } returns PostSourceBinding(PostFixtures.postId(1), managed)
        every { posts.find(PostFixtures.postId(1)) } returns PostFixtures.privatePost(PostFixtures.postId(1))
        every { posts.findBinding(legacy) } returns PostSourceBinding(PostFixtures.postId(2), legacy)
        every { posts.find(PostFixtures.postId(2)) } returns null
        every { posts.findBinding(fresh) } returns null
        every { posts.find(generatedId) } returns null

        val result =
            service.registerCatalog(
                listOf(
                    CatalogEntry(managed, "Managed"),
                    CatalogEntry(legacy, "Legacy"),
                    CatalogEntry(fresh, "Fresh"),
                    CatalogEntry(fresh, "Fresh again"),
                ),
            )

        assertThat(result).isEqualTo(CatalogSynchronization(registered = 2))
    }

    @Test
    fun `a scan of documents that are all managed registers nothing`() {
        val managed = PostFixtures.document("managed")
        every { posts.findBinding(managed) } returns PostSourceBinding(PostFixtures.postId(1), managed)
        every { posts.find(PostFixtures.postId(1)) } returns PostFixtures.publicPost(PostFixtures.postId(1), PostFixtures.revisionId(1))

        val result = service.registerCatalog(listOf(CatalogEntry(managed, "Managed")))

        assertThat(result).isEqualTo(CatalogSynchronization(registered = 0))
    }
}
