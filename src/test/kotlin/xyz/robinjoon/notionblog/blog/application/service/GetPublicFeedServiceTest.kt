package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.PublicFeed
import xyz.robinjoon.notionblog.blog.application.model.PublicFeedLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PublishedPost
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.fixture.SiteFixtures

class GetPublicFeedServiceTest {
    private val sites = mockk<SiteConfigurationRepository>()
    private val chromes = mockk<SiteChromeService>()
    private val published = mockk<PublishedPostStore>()
    private val failures = mockk<SnapshotFailureReporter>()
    private val service = GetPublicFeedService(sites, chromes, published, failures)
    private val site = SiteFixtures.site()
    private val excluded = setOf(PostFixtures.postId(9))

    @Test
    fun `the feed holds the newest twenty public posts other than site documents`() {
        val entry = PublishedPost(PostFixtures.revision(PostFixtures.postId(1), PostFixtures.revisionId(1)), PostFixtures.capturedAt)
        every { sites.findCurrent() } returns site
        every { chromes.siteDocumentPostIds(site) } returns excluded
        every { published.findRecent(excluded, 20) } returns listOf(entry)

        assertThat(service.get()).isEqualTo(PublicFeedLookupResult.Found(PublicFeed(site.metadata, listOf(entry))))
    }

    @Test
    fun `the feed is unavailable without a site or with an undecodable revision`() {
        every { sites.findCurrent() } returns null

        assertThat(service.get()).isEqualTo(PublicFeedLookupResult.ContentUnavailable)

        every { sites.findCurrent() } returns site
        every { chromes.siteDocumentPostIds(site) } returns excluded
        every { published.findRecent(excluded, 20) } throws SnapshotContentException("broken")
        every { failures.report(any(), any(), any()) } just Runs

        assertThat(service.get()).isEqualTo(PublicFeedLookupResult.ContentUnavailable)
    }
}
