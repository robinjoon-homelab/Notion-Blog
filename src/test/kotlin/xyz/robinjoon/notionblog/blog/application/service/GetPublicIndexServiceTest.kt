package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.PostFeedEntry
import xyz.robinjoon.notionblog.blog.application.model.PublicIndex
import xyz.robinjoon.notionblog.blog.application.model.PublicIndexEntry
import xyz.robinjoon.notionblog.blog.application.model.PublicIndexLookupResult
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

class GetPublicIndexServiceTest {
    private val snapshotFailures = mockk<SnapshotFailureReporter>(relaxed = true)
    private val posts = mockk<PostRepository>()
    private val publications = mockk<PublicationRepository>()
    private val siteConfigurations = mockk<SiteConfigurationRepository>()
    private val service =
        GetPublicIndexService(ActivePublicationScopeReader(posts, publications, siteConfigurations), posts, snapshotFailures)
    private val publicationId = PublicationId(UUID.randomUUID())
    private val rootPostId = PostId(UUID.randomUUID())
    private val revisionId = PublicationRevisionId(UUID.randomUUID())
    private val publication = BlogPublication.restore(publicationId, rootPostId, revisionId)
    private val site =
        SiteConfiguration.restore(
            publicationId = publicationId,
            documents = SiteDocuments(root = sourceDocument("root"), header = null, footer = null),
            metadata = SiteMetadata("My blog", "New posts", "ko-KR", null),
            presentationProfile = PresentationProfileRef(PresentationProfileId(UUID.randomUUID()), 1),
        )

    @Test
    fun `places the root first and keeps the remaining posts in repository order`() {
        arrangeActiveSite()
        val newer = entry(PostId(UUID.randomUUID()), "Newer", "2026-10-07T00:00:00Z")
        val root = entry(rootPostId, "Root", "2026-10-06T00:00:00Z")
        val older = entry(PostId(UUID.randomUUID()), "Older", "2026-10-05T00:00:00Z")
        every { posts.findPublishedPosts(publicationId, emptySet()) } returns listOf(newer, root, older)

        assertThat(service.get()).isEqualTo(
            PublicIndexLookupResult.Found(
                PublicIndex(site.metadata, listOf(indexEntry(root, true), indexEntry(newer, false), indexEntry(older, false))),
            ),
        )
    }

    @Test
    fun `excludes header and footer documents but never the root`() {
        val header = sourceDocument("header")
        val footer = sourceDocument("footer")
        val headerPostId = PostId(UUID.randomUUID())
        val footerPostId = PostId(UUID.randomUUID())
        val configuration = site.with(documents = site.documents.copy(header = header, footer = footer))
        arrangeActiveSite(configuration)
        every { posts.findBindingsBySourceDocuments(setOf(header, footer)) } returns
            mapOf(header to PostSourceBinding(headerPostId, header), footer to PostSourceBinding(footerPostId, footer))
        val article = entry(PostId(UUID.randomUUID()), "Article", "2026-10-05T00:00:00Z")
        val root = entry(rootPostId, "Root", "2026-10-04T00:00:00Z")
        every { posts.findPublishedPosts(publicationId, setOf(headerPostId, footerPostId)) } returns listOf(article, root)

        assertThat(service.get()).isEqualTo(
            PublicIndexLookupResult.Found(
                PublicIndex(configuration.metadata, listOf(indexEntry(root, true), indexEntry(article, false))),
            ),
        )
    }

    @Test
    fun `keeps the root when it is also configured as the header`() {
        val rootDocument = site.documents.root
        val configuration = site.with(documents = site.documents.copy(header = rootDocument))
        arrangeActiveSite(configuration)
        every { posts.findBindingsBySourceDocuments(setOf(rootDocument)) } returns
            mapOf(rootDocument to PostSourceBinding(rootPostId, rootDocument))
        val root = entry(rootPostId, "Root", "2026-10-04T00:00:00Z")
        every { posts.findPublishedPosts(publicationId, emptySet()) } returns listOf(root)

        assertThat(service.get()).isEqualTo(
            PublicIndexLookupResult.Found(PublicIndex(configuration.metadata, listOf(indexEntry(root, true)))),
        )
    }

    @Test
    fun `lists published descendants without a root entry when the root is not public`() {
        arrangeActiveSite()
        val article = entry(PostId(UUID.randomUUID()), "Article", "2026-10-05T00:00:00Z")
        every { posts.findPublishedPosts(publicationId, emptySet()) } returns listOf(article)

        assertThat(service.get()).isEqualTo(
            PublicIndexLookupResult.Found(PublicIndex(site.metadata, listOf(indexEntry(article, false)))),
        )
    }

    @Test
    fun `missing or mismatched active scope is unavailable`() {
        every { siteConfigurations.findCurrent() } returns null
        assertThat(service.get()).isEqualTo(PublicIndexLookupResult.ContentUnavailable)

        every { siteConfigurations.findCurrent() } returns site.with(publicationId = PublicationId(UUID.randomUUID()))
        every { publications.findCurrent() } returns publication
        assertThat(service.get()).isEqualTo(PublicIndexLookupResult.ContentUnavailable)

        arrangeActiveSite()
        every { publications.findActiveRevision(publicationId) } returns
            PublicationRevision(revisionId, publicationId, PublicationRevisionState.STAGING)
        assertThat(service.get()).isEqualTo(PublicIndexLookupResult.ContentUnavailable)
    }

    @Test
    fun `corrupt snapshot makes the whole index unavailable`() {
        arrangeActiveSite()
        every { posts.findPublishedPosts(publicationId, emptySet()) } throws
            SnapshotContentException("unsupported snapshot", IllegalArgumentException("invalid content"))

        assertThat(service.get()).isEqualTo(PublicIndexLookupResult.ContentUnavailable)
    }

    private fun arrangeActiveSite(configuration: SiteConfiguration = site) {
        every { siteConfigurations.findCurrent() } returns configuration
        every { publications.findCurrent() } returns publication
        every { publications.findActiveRevision(publicationId) } returns
            PublicationRevision(revisionId, publicationId, PublicationRevisionState.ACTIVE)
        every { posts.findBindingsBySourceDocuments(emptySet()) } returns emptyMap()
    }

    private fun sourceDocument(externalId: String) = SourceDocumentRef(SourceId("notion"), externalId)

    private fun SiteConfiguration.with(
        publicationId: PublicationId = this.publicationId,
        documents: SiteDocuments = this.documents,
    ): SiteConfiguration = SiteConfiguration.restore(publicationId, documents, metadata, presentationProfile)

    private fun entry(
        id: PostId,
        title: String,
        publishedAt: String,
    ) = PostFeedEntry(
        Post.restore(id, title, BlockTree(emptyList())),
        Instant.parse(publishedAt),
        Instant.parse(publishedAt).plusSeconds(60),
    )

    private fun indexEntry(
        entry: PostFeedEntry,
        root: Boolean,
    ) = PublicIndexEntry(entry.post, root, entry.firstPublishedAt, entry.lastModifiedAt)
}
