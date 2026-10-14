package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.PostFeed
import xyz.robinjoon.notionblog.blog.application.model.PostFeedEntry
import xyz.robinjoon.notionblog.blog.application.model.PostFeedLookupResult
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublicationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileRef
import xyz.robinjoon.notionblog.blog.domain.publication.BlogPublication
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationRevision
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationRevisionId
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationRevisionState
import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.blog.domain.site.SiteDocuments
import xyz.robinjoon.notionblog.blog.domain.site.SiteMetadata
import java.time.Instant
import java.util.UUID

class GetPostFeedServiceTest {
    private val snapshotFailures = mockk<SnapshotFailureReporter>(relaxed = true)
    private val posts = mockk<PostRepository>()
    private val publications = mockk<PublicationRepository>()
    private val siteConfigurations = mockk<SiteConfigurationRepository>()
    private val service =
        GetPostFeedService(ActivePublicationScopeReader(posts, publications, siteConfigurations), posts, snapshotFailures)
    private val publicationId = PublicationId(UUID.randomUUID())
    private val rootPostId = PostId(UUID.randomUUID())
    private val revisionId = PublicationRevisionId(UUID.randomUUID())
    private val publication = BlogPublication.restore(publicationId, rootPostId, revisionId)
    private val activeRevision = PublicationRevision(revisionId, publicationId, PublicationRevisionState.ACTIVE)
    private val site =
        SiteConfiguration.restore(
            publicationId = publicationId,
            documents = SiteDocuments(root = sourceDocument("root"), header = null, footer = null),
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
        every { publications.findCurrent() } returns BlogPublication.restore(publicationId, null, null)

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.ContentUnavailable)
    }

    @Test
    fun `site and current publication mismatch is unavailable`() {
        every { siteConfigurations.findCurrent() } returns site.with(publicationId = PublicationId(UUID.randomUUID()))
        every { publications.findCurrent() } returns publication

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.ContentUnavailable)
    }

    @Test
    fun `missing active revision is unavailable rather than an empty feed`() {
        arrangeActiveSite()
        every { publications.findActiveRevision(publicationId) } returns null

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.ContentUnavailable)
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
    }

    @Test
    fun `initialized site with no feed entries returns an empty normal feed`() {
        arrangeActiveSite()
        every { posts.findRecentPublishedPosts(publicationId, setOf(rootPostId), 20) } returns emptyList()

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.Found(PostFeed(site.metadata, emptyList())))
    }

    @Test
    fun `returns recent posts without requiring published root or presentation assets`() {
        arrangeActiveSite()
        val entries = listOf(entry("Published descendant"), entry("Owned database row"))
        every { posts.findRecentPublishedPosts(publicationId, setOf(rootPostId), 20) } returns entries

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.Found(PostFeed(site.metadata, entries)))
    }

    @Test
    fun `excludes only active root and current layout bindings while allowing their descendants`() {
        val header = sourceDocument("header")
        val footer = sourceDocument("footer")
        val headerPostId = PostId(UUID.randomUUID())
        val footerPostId = PostId(UUID.randomUUID())
        val configuration = site.with(documents = site.documents.copy(header = header, footer = footer))
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
    }

    @Test
    fun `missing layout bindings do not make the feed unavailable`() {
        val header = sourceDocument("missing-header")
        val footer = sourceDocument("footer")
        val footerPostId = PostId(UUID.randomUUID())
        val configuration = site.with(documents = site.documents.copy(header = header, footer = footer))
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
        val root = site.documents.root
        val configuration = site.with(documents = site.documents.copy(header = root, footer = root))
        arrangeActiveSite(configuration)
        every { posts.findBindingsBySourceDocuments(setOf(root)) } returns
            mapOf(
                root to PostSourceBinding(rootPostId, root),
            )
        every { posts.findRecentPublishedPosts(publicationId, setOf(rootPostId), 20) } returns emptyList()

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.Found(PostFeed(configuration.metadata, emptyList())))
    }

    @Test
    fun `pending root change keeps old active scope but applies current metadata and layout exclusions`() {
        val header = sourceDocument("new-header")
        val newHeaderPostId = PostId(UUID.randomUUID())
        val configuration =
            site.with(
                documents = site.documents.copy(root = sourceDocument("pending-root"), header = header),
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
    fun `corrupt feed snapshot makes the whole feed unavailable`() {
        arrangeActiveSite()
        val failure = SnapshotContentException("unsupported snapshot", IllegalArgumentException("invalid content"))
        every {
            posts.findRecentPublishedPosts(publicationId, setOf(rootPostId), 20)
        } throws failure

        assertThat(service.get()).isEqualTo(PostFeedLookupResult.ContentUnavailable)
    }

    private fun arrangeActiveSite(configuration: SiteConfiguration = site) {
        every { siteConfigurations.findCurrent() } returns configuration
        every { publications.findCurrent() } returns publication
        every { publications.findActiveRevision(publicationId) } returns activeRevision
        every { posts.findBindingsBySourceDocuments(emptySet()) } returns emptyMap()
    }

    private fun sourceDocument(externalId: String) = SourceDocumentRef(SourceId("notion"), externalId)

    // Restores a stored variant of the configuration; the root keeps its copy private.
    private fun SiteConfiguration.with(
        publicationId: PublicationId = this.publicationId,
        documents: SiteDocuments = this.documents,
        metadata: SiteMetadata = this.metadata,
    ): SiteConfiguration = SiteConfiguration.restore(publicationId, documents, metadata, presentationProfile)

    private fun entry(title: String) =
        PostFeedEntry(
            Post.restore(PostId(UUID.randomUUID()), title, BlockTree(emptyList())),
            Instant.parse("2026-10-05T03:00:00Z"),
            Instant.parse("2026-10-06T03:00:00Z"),
        )
}
