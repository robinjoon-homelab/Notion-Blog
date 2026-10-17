package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PostListLookupResult
import xyz.robinjoon.notionblog.blog.application.model.ResultPage
import xyz.robinjoon.notionblog.blog.application.model.RevisionMedia
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.fixture.SiteFixtures

class GetPostListServiceTest {
    private val chromes = mockk<SiteChromeService>()
    private val references = mockk<ContentReferencesService>()
    private val published = mockk<PublishedPostStore>()
    private val failures = mockk<SnapshotFailureReporter>()
    private val service = GetPostListService(chromes, references, published, failures)
    private val chrome = SiteFixtures.chrome()
    private val page = PageQuery(1, 10)
    private val excluded = setOf(PostFixtures.postId(9))

    @Test
    fun `lists public posts without site documents and resolves each cover through its own revision`() {
        val cover = PostFixtures.hosted("cover.png")
        val first = entry(1, cover)
        val second = entry(2, cover)
        val name = SourceMediaKey.of(cover.url)
        val covers =
            RevisionMedia(mapOf(first.revisionId to mapOf(name to key('a')), second.revisionId to mapOf(name to key('b'))))
        givenChrome()
        every { published.findListPage(excluded, page) } returns ResultPage(listOf(first, second), 1, 10, 2)
        every { references.media(setOf(first.revisionId, second.revisionId)) } returns covers

        val found = (service.get(page) as PostListLookupResult.Found).page

        assertThat(found.entries.items).containsExactly(first, second)
        assertThat(found.references.media.keyFor(first.revisionId, cover)).isEqualTo(key('a'))
        assertThat(found.references.media.keyFor(second.revisionId, cover)).isEqualTo(key('b'))
    }

    private fun entry(
        label: Int,
        cover: MediaSource.SourceHosted,
    ) = PostListEntry(
        PostFixtures.postId(label),
        PostFixtures.revisionId(label),
        PostFixtures.properties(cover = cover),
        PostFixtures.capturedAt,
    )

    private fun key(digit: Char) = MediaKey(digit.toString().repeat(64))

    @Test
    fun `the list is unavailable without a site or when stored properties cannot be read`() {
        every { chromes.load() } returns null

        assertThat(service.get(page)).isEqualTo(PostListLookupResult.ContentUnavailable)

        givenChrome()
        every { published.findListPage(excluded, page) } throws SnapshotContentException("broken")
        every { failures.report(any(), any(), any()) } just Runs

        assertThat(service.get(page)).isEqualTo(PostListLookupResult.ContentUnavailable)
    }

    private fun givenChrome() {
        every { chromes.load() } returns chrome
        every { chromes.siteDocumentPostIds(chrome.site) } returns excluded
        every { references.resolve(emptyList()) } returns SiteFixtures.noReferences()
    }
}
