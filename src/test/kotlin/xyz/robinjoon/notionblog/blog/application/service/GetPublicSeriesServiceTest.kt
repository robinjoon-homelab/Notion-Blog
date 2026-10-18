package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PublicSeries
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesListLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesSummary
import xyz.robinjoon.notionblog.blog.application.model.RevisionMedia
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.HomeConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedSeriesStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.fixture.SiteFixtures
import java.util.UUID

class GetPublicSeriesServiceTest {
    private val chromes = mockk<SiteChromeService>()
    private val references = mockk<ContentReferencesService>()
    private val published = mockk<PublishedSeriesStore>()
    private val homes = mockk<HomeConfigurationRepository>()
    private val failures = mockk<SnapshotFailureReporter>()
    private val service = GetPublicSeriesService(chromes, references, published, homes, failures)
    private val chrome = SiteFixtures.chrome()
    private val excluded = setOf(PostFixtures.postId(9))
    private val seriesId = SeriesId(UUID.fromString("00000000-0000-0000-0001-000000000001"))
    private val cover = PostFixtures.hosted("cover.png")
    private val first = entry(1)

    @Test
    fun `the series list carries each first public post's cover through its own revision`() {
        val summary = PublicSeriesSummary(seriesId, "DDD", "", 2, first)
        givenChrome()
        every { published.findAll(excluded) } returns listOf(summary)
        every { references.media(setOf(first.revisionId)) } returns covers()

        val found = (service.list() as PublicSeriesListLookupResult.Found).page

        assertThat(found.series).containsExactly(summary)
        assertThat(found.references.media.keyFor(first.revisionId, cover)).isEqualTo(key())
    }

    @Test
    fun `a series page holds its public posts in order with the stored home settings`() {
        val second = entry(2)
        val series = PublicSeries(seriesId, "DDD", "", listOf(first, second))
        givenChrome()
        every { published.find(seriesId, excluded) } returns series
        every { references.media(setOf(first.revisionId, second.revisionId)) } returns covers()
        every { homes.find() } returns null

        val found = (service.get(seriesId) as PublicSeriesLookupResult.Found).page

        assertThat(found.series).isEqualTo(series)
        assertThat(found.configuration).isEqualTo(HomeConfiguration.defaults())
        assertThat(found.references.media.keyFor(first.revisionId, cover)).isEqualTo(key())
    }

    @Test
    fun `a series without public posts is not found`() {
        givenChrome()
        every { published.find(seriesId, excluded) } returns null

        assertThat(service.get(seriesId)).isEqualTo(PublicSeriesLookupResult.NotFound)
    }

    @Test
    fun `series are unavailable without a site or when stored properties cannot be read`() {
        every { chromes.load() } returns null

        assertThat(service.list()).isEqualTo(PublicSeriesListLookupResult.ContentUnavailable)
        assertThat(service.get(seriesId)).isEqualTo(PublicSeriesLookupResult.ContentUnavailable)

        givenChrome()
        every { published.findAll(excluded) } throws SnapshotContentException("broken")
        every { published.find(seriesId, excluded) } throws SnapshotContentException("broken")
        every { failures.report(any(), any(), any()) } just Runs

        assertThat(service.list()).isEqualTo(PublicSeriesListLookupResult.ContentUnavailable)
        assertThat(service.get(seriesId)).isEqualTo(PublicSeriesLookupResult.ContentUnavailable)
    }

    private fun givenChrome() {
        every { chromes.load() } returns chrome
        every { chromes.siteDocumentPostIds(chrome.site) } returns excluded
        every { references.resolve(emptyList()) } returns SiteFixtures.noReferences()
    }

    private fun entry(label: Int) =
        PostListEntry(
            PostFixtures.postId(label),
            PostFixtures.revisionId(label),
            PostFixtures.properties(cover = cover),
            PostFixtures.capturedAt,
        )

    private fun covers() = RevisionMedia(mapOf(first.revisionId to mapOf(SourceMediaKey.of(cover.url) to key())))

    private fun key() = MediaKey("a".repeat(64))
}
