package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.PublicPostLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicPostPage
import xyz.robinjoon.notionblog.blog.application.model.PublishedPost
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.fixture.SiteFixtures

class GetPublicPostServiceTest {
    private val chromes = mockk<SiteChromeService>()
    private val references = mockk<ContentReferencesService>()
    private val published = mockk<PublishedPostStore>()
    private val failures = mockk<SnapshotFailureReporter>()
    private val service = GetPublicPostService(chromes, references, published, failures)
    private val postId = PostFixtures.postId(1)
    private val post = PublishedPost(PostFixtures.revision(postId, PostFixtures.revisionId(1)), PostFixtures.capturedAt)

    @Test
    fun `a post that is not public is not found`() {
        every { published.find(postId) } returns null

        assertThat(service.get(postId)).isEqualTo(PublicPostLookupResult.NotFound)
    }

    @Test
    fun `a public post is served with the site chrome and the references of everything shown`() {
        val header = PostFixtures.revision(PostFixtures.postId(2), PostFixtures.revisionId(2))
        val chrome = SiteFixtures.chrome(header)
        every { published.find(postId) } returns post
        every { chromes.load() } returns chrome
        every { references.resolve(listOf(post.revision, header)) } returns SiteFixtures.noReferences()

        assertThat(service.get(postId))
            .isEqualTo(PublicPostLookupResult.Found(PublicPostPage(chrome, post, SiteFixtures.noReferences())))
    }

    @Test
    fun `a missing site or an undecodable revision is unavailable`() {
        every { published.find(postId) } returns post
        every { chromes.load() } returns null

        assertThat(service.get(postId)).isEqualTo(PublicPostLookupResult.ContentUnavailable)

        every { published.find(postId) } throws SnapshotContentException("broken")
        every { failures.report(any(), any(), any()) } just Runs

        assertThat(service.get(postId)).isEqualTo(PublicPostLookupResult.ContentUnavailable)
    }
}
