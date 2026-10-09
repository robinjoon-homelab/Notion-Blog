package xyz.robinjoon.notionblog.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.application.model.PostFeed
import xyz.robinjoon.notionblog.application.model.PostFeedEntry
import xyz.robinjoon.notionblog.application.model.PostFeedLookupResult
import xyz.robinjoon.notionblog.application.port.output.diagnostics.SnapshotFailureOperation
import xyz.robinjoon.notionblog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.publication.BlogPublication
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.publication.PublicationRevision
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.domain.publication.PublicationRevisionState
import xyz.robinjoon.notionblog.domain.site.PresentationProfileId
import xyz.robinjoon.notionblog.domain.site.PresentationProfileRef
import xyz.robinjoon.notionblog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.domain.site.SiteMetadata
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.time.Instant
import java.util.UUID

class GetPostFeedServiceTest {
    private val snapshotFailures = mockk<SnapshotFailureReporter>(relaxed = true)
    private val posts = mockk<PostRepository>()
    private val publications = mockk<PublicationRepository>()
    private val siteConfigurations = mockk<SiteConfigurationRepository>()
    private val service = GetPostFeedService(posts, publications, siteConfigurations, snapshotFailures)
    private val publicationId = PublicationId(UUID.randomUUID())
    private val rootPostId = PostId(UUID.randomUUID())
    private val revisionId = PublicationRevisionId(UUID.randomUUID())
    private val publication = BlogPublication(publicationId, rootPostId, revisionId)
    private val activeRevision = PublicationRevision(revisionId, publicationId, PublicationRevisionState.ACTIVE)
    private val site =
        SiteConfiguration(
            publicationId = publicationId,
            rootDocument = sourceDocument("root"),
            headerDocument = null,
            footerDocument = null,
            metadata = SiteMetadata("My blog", "New posts", "ko-KR", null),
            presentationProfile = PresentationProfileRef(PresentationProfileId(UUID.randomUUID()), 1),
        )

    @Test
    fun `missing site configuration is unavailable`() {
        every { siteConfigurations.findCurrent() } returns null

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.ContentUnavailable)
    }

    @Test
    fun `missing publication is unavailable`() {
        every { siteConfigurations.findCurrent() } returns site
        every { publications.findCurrent() } returns null

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.ContentUnavailable)
    }

    @Test
    fun `publication without an active scope is unavailable`() {
        every { siteConfigurations.findCurrent() } returns site
        every { publications.findCurrent() } returns BlogPublication(publicationId, null, null)

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.ContentUnavailable)
    }

    @Test
    fun `site and current publication mismatch is unavailable`() {
        every { siteConfigurations.findCurrent() } returns site.copy(publicationId = PublicationId(UUID.randomUUID()))
        every { publications.findCurrent() } returns publication

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.ContentUnavailable)
    }

    @Test
    fun `missing active revision is unavailable rather than an empty feed`() {
        arrangeActiveSite()
        every { publications.findActiveRevision(publicationId) } returns null

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.ContentUnavailable)
        verify(exactly = 0) { posts.findRecentPublishedPosts(any(), any(), any()) }
    }

    @Test
    fun `active revision must match the pointer publication and active state`() {
        arrangeActiveSite()
        val invalidRevisions =
            listOf(
                activeRevision.copy(id = PublicationRevisionId(UUID.randomUUID())),
                activeRevision.copy(publicationId = PublicationId(UUID.randomUUID())),
                activeRevision.copy(state = PublicationRevisionState.STAGING),
                activeRevision.copy(state = PublicationRevisionState.SUPERSEDED),
                activeRevision.copy(state = PublicationRevisionState.ABANDONED),
            )

        for (revision in invalidRevisions) {
            every { publications.findActiveRevision(publicationId) } returns revision

            assertThat(service.get()).describedAs("revision %s", revision).isEqualTo(PostFeedLookupResult.ContentUnavailable)
        }
        verify(exactly = 0) { posts.findRecentPublishedPosts(any(), any(), any()) }
    }

    @Test
    fun `initialized site with no feed entries returns an empty normal feed`() {
        arrangeActiveSite()
        every { posts.findRecentPublishedPosts(publicationId, setOf(rootPostId), 20) } returns emptyList()

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.Found(PostFeed(site.metadata, emptyList())))
        verify(exactly = 0) { snapshotFailures.report(any(), any(), any()) }
    }

    @Test
    fun `returns recent posts without requiring published root or presentation assets`() {
        arrangeActiveSite()
        val entries = listOf(entry("Published descendant"), entry("Owned database row"))
        every { posts.findRecentPublishedPosts(publicationId, setOf(rootPostId), 20) } returns entries

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.Found(PostFeed(site.metadata, entries)))
        verify(exactly = 0) { posts.findAvailability(any()) }
        verify(exactly = 0) { posts.find(any()) }
        verify(exactly = 0) { siteConfigurations.findProfile(any()) }
        verify(exactly = 0) { siteConfigurations.findCurrentProfile(any()) }
    }

    @Test
    fun `excludes only active root and current layout bindings while allowing their descendants`() {
        val header = sourceDocument("header")
        val footer = sourceDocument("footer")
        val headerPostId = PostId(UUID.randomUUID())
        val footerPostId = PostId(UUID.randomUUID())
        val configuration = site.copy(headerDocument = header, footerDocument = footer)
        arrangeActiveSite(configuration)
        every { posts.findBindingsBySourceDocuments(setOf(header, footer)) } returns
            mapOf(
                header to PostSourceBinding(headerPostId, header),
                footer to PostSourceBinding(footerPostId, footer),
            )
        val entries = listOf(entry("Header descendant"), entry("Footer descendant"))
        every {
            posts.findRecentPublishedPosts(publicationId, setOf(rootPostId, headerPostId, footerPostId), 20)
        } returns entries

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.Found(PostFeed(configuration.metadata, entries)))
        verify(exactly = 1) { posts.findBindingsBySourceDocuments(setOf(header, footer)) }
        verify(exactly = 0) { publications.findActiveDirectChildren(any(), any()) }
        verify(exactly = 0) { publications.findMembers(any()) }
    }

    @Test
    fun `missing layout bindings do not make the feed unavailable`() {
        val header = sourceDocument("missing-header")
        val footer = sourceDocument("footer")
        val footerPostId = PostId(UUID.randomUUID())
        val configuration = site.copy(headerDocument = header, footerDocument = footer)
        arrangeActiveSite(configuration)
        every { posts.findBindingsBySourceDocuments(setOf(header, footer)) } returns
            mapOf(
                footer to PostSourceBinding(footerPostId, footer),
            )
        val entries = listOf(entry("Article"))
        every { posts.findRecentPublishedPosts(publicationId, setOf(rootPostId, footerPostId), 20) } returns entries

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.Found(PostFeed(configuration.metadata, entries)))
    }

    @Test
    fun `duplicate root header and footer references are excluded once`() {
        val configuration = site.copy(headerDocument = site.rootDocument, footerDocument = site.rootDocument)
        arrangeActiveSite(configuration)
        every { posts.findBindingsBySourceDocuments(setOf(site.rootDocument)) } returns
            mapOf(
                site.rootDocument to PostSourceBinding(rootPostId, site.rootDocument),
            )
        every { posts.findRecentPublishedPosts(publicationId, setOf(rootPostId), 20) } returns emptyList()

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.Found(PostFeed(configuration.metadata, emptyList())))
    }

    @Test
    fun `pending root change keeps old active scope but applies current metadata and layout exclusions`() {
        val header = sourceDocument("new-header")
        val newHeaderPostId = PostId(UUID.randomUUID())
        val configuration =
            site.copy(
                rootDocument = sourceDocument("pending-root"),
                headerDocument = header,
                metadata = site.metadata.copy(siteName = "Updated blog"),
            )
        arrangeActiveSite(configuration)
        every { posts.findBindingsBySourceDocuments(setOf(header)) } returns
            mapOf(
                header to PostSourceBinding(newHeaderPostId, header),
            )
        val entries = listOf(entry("Former header is now an ordinary article"))
        every { posts.findRecentPublishedPosts(publicationId, setOf(rootPostId, newHeaderPostId), 20) } returns entries

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.Found(PostFeed(configuration.metadata, entries)))
    }

    @Test
    fun `reports corrupt feed snapshot without guessing a post identity and makes the whole feed unavailable`() {
        arrangeActiveSite()
        val failure = SnapshotContentException("unsupported snapshot", IllegalArgumentException("invalid content"))
        val reported = slot<SnapshotContentException>()
        every {
            posts.findRecentPublishedPosts(publicationId, setOf(rootPostId), 20)
        } throws failure

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.ContentUnavailable)
        verify(exactly = 1) { snapshotFailures.report(capture(reported), SnapshotFailureOperation.FEED_LOOKUP, null) }
        assertThat(reported.captured).isSameAs(failure)
    }

    @Test
    fun `feed service owns a read only repeatable read transaction`() {
        val transaction = GetPostFeedService::class.java.getMethod("get").getAnnotation(Transactional::class.java)

        assertThat(GetPostFeedService::class.java.isAnnotationPresent(Service::class.java)).isFalse()
        assertThat(transaction?.readOnly).isTrue()
        assertThat(transaction?.isolation).isEqualTo(Isolation.REPEATABLE_READ)
    }

    private fun arrangeActiveSite(configuration: SiteConfiguration = site) {
        every { siteConfigurations.findCurrent() } returns configuration
        every { publications.findCurrent() } returns publication
        every { publications.findActiveRevision(publicationId) } returns activeRevision
        every { posts.findBindingsBySourceDocuments(emptySet()) } returns emptyMap()
    }

    private fun sourceDocument(externalId: String) = SourceDocumentRef(SourceId("notion"), externalId)

    private fun entry(title: String) =
        PostFeedEntry(
            Post(PostId(UUID.randomUUID()), title, BlockTree(emptyList())),
            Instant.parse("2026-10-05T03:00:00Z"),
        )
}
