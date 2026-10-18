package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.ListedCategory
import xyz.robinjoon.notionblog.blog.application.model.PublicPostLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicPostPage
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesNavigation
import xyz.robinjoon.notionblog.blog.application.model.PublishedPost
import xyz.robinjoon.notionblog.blog.application.model.SeriesPostLink
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedSeriesStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.fixture.SiteFixtures
import java.util.UUID

class GetPublicPostServiceTest {
    private val chromes = mockk<SitePresentationContextService>()
    private val references = mockk<ContentReferencesService>()
    private val published = mockk<PublishedPostStore>()
    private val series = mockk<PublishedSeriesStore>()
    private val failures = mockk<SnapshotFailureReporter>()
    private val service = GetPublicPostService(chromes, references, published, series, failures)
    private val postId = PostFixtures.postId(1)
    private val post = PublishedPost(PostFixtures.revision(postId, PostFixtures.revisionId(1)), PostFixtures.capturedAt)

    @Test
    fun `a post that is not public is not found`() {
        every { published.find(postId) } returns null

        assertThat(service.get(postId)).isEqualTo(PublicPostLookupResult.NotFound)
    }

    @Test
    fun `a public post is served with its app tags, its public series trail and the references of everything shown`() {
        val header = PostFixtures.revision(PostFixtures.postId(2), PostFixtures.revisionId(2))
        val chrome = SiteFixtures.chrome(header)
        val tags = listOf(ListedCategory(CategoryId(UUID.fromString("00000000-0000-0000-0000-000000000009")), "Kotlin"))
        val trail =
            PublicSeriesNavigation(
                SeriesId(UUID.fromString("00000000-0000-0000-0001-000000000001")),
                "DDD",
                listOf(SeriesPostLink(postId, "Title"), SeriesPostLink(PostFixtures.postId(3), "Next")),
                postId,
            )
        val siteDocuments = setOf(PostFixtures.postId(2))
        every { published.find(postId) } returns post
        every { chromes.load() } returns chrome
        every { chromes.siteDocumentPostIds(chrome.site) } returns siteDocuments
        every { references.resolve(listOf(post.revision, header)) } returns SiteFixtures.noReferences()
        every { published.findCategories(postId) } returns tags
        every { series.findNavigation(postId, siteDocuments) } returns trail

        assertThat(service.get(postId))
            .isEqualTo(PublicPostLookupResult.Found(PublicPostPage(chrome, post, SiteFixtures.noReferences(), tags, trail)))
    }

    @Test
    fun `a post outside any public series trail has none`() {
        val chrome = SiteFixtures.chrome()
        every { published.find(postId) } returns post
        every { chromes.load() } returns chrome
        every { chromes.siteDocumentPostIds(chrome.site) } returns emptySet()
        every { references.resolve(listOf(post.revision)) } returns SiteFixtures.noReferences()
        every { published.findCategories(postId) } returns emptyList()
        every { series.findNavigation(postId, emptySet()) } returns null

        val found = service.get(postId) as PublicPostLookupResult.Found

        assertThat(found.page.series).isNull()
        assertThat(found.page.categories).isEmpty()
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
