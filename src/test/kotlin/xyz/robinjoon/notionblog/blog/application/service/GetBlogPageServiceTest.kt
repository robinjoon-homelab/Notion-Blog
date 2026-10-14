package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.BlogPageLookupResult
import xyz.robinjoon.notionblog.blog.application.model.LinkResolution
import xyz.robinjoon.notionblog.blog.application.model.PostLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PresentationProfileRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssets
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfile
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileId
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileKey
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfileRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationTokens
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.blog.domain.site.SiteDocuments
import xyz.robinjoon.notionblog.blog.domain.site.SiteMetadata
import java.time.Instant
import java.util.UUID

class GetBlogPageServiceTest {
    private val publishedPosts = mockk<GetPublishedPostService>()
    private val postRepository = mockk<PostRepository>()
    private val siteConfigurations = mockk<SiteConfigurationRepository>()
    private val profiles = mockk<PresentationProfileRepository>()
    private val assets = mockk<PresentationAssetCatalog>()
    private val links = mockk<ResolvePostLinksService>()
    private val service = GetBlogPageService(publishedPosts, postRepository, siteConfigurations, profiles, assets, links)
    private val publishedAt = Instant.parse("2026-10-01T00:00:00Z")
    private val modifiedAt = Instant.parse("2026-10-02T00:00:00Z")

    @Test
    fun `builds a page with publishable header footer trusted assets and resolved links`() {
        val body = post("body")
        val header = post("header")
        val footer = post("footer")
        val headerDocument = sourceDocument("header")
        val footerDocument = sourceDocument("footer")
        val configuration = configuration(headerDocument = headerDocument, footerDocument = footerDocument)
        val profile = profile()
        val style = profile.assets.styleSheets.single()
        val script = profile.assets.scripts.single()
        val favicon = requireNotNull(configuration.metadata.favicon)
        every { publishedPosts.get(body.id) } returns found(body)
        every { siteConfigurations.findCurrent() } returns configuration
        every { profiles.findProfile(configuration.presentationProfile) } returns profile
        every { postRepository.findBinding(headerDocument) } returns binding(header, headerDocument)
        every { postRepository.findBinding(footerDocument) } returns binding(footer, footerDocument)
        every { publishedPosts.get(header.id) } returns found(header)
        every { publishedPosts.get(footer.id) } returns found(footer)
        every { assets.resolve(style) } returns descriptor(style)
        every { assets.resolve(script) } returns descriptor(script)
        every { assets.resolve(favicon) } returns descriptor(favicon)
        every { links.resolve(configuration.publicationId, listOf(body.content, header.content, footer.content)) } returns emptyMap()

        val result = service.get(body.id)

        assertThat(result).isEqualTo(
            BlogPageLookupResult.Found(
                xyz.robinjoon.notionblog.blog.application.model.BlogPage(
                    site = configuration,
                    presentation = profile,
                    presentationAssets = mapOf(style to descriptor(style), script to descriptor(script), favicon to descriptor(favicon)),
                    post = body,
                    header = header,
                    footer = footer,
                    links = emptyMap(),
                    firstPublishedAt = publishedAt,
                    lastModifiedAt = modifiedAt,
                    root = false,
                ),
            ),
        )
    }

    @Test
    fun `carries the root marker and post times into the page`() {
        val body = post("root")
        val configuration = configuration()
        every { publishedPosts.getRoot() } returns PostLookupResult.Found(body, null, modifiedAt, root = true)
        every { siteConfigurations.findCurrent() } returns configuration
        every { profiles.findProfile(configuration.presentationProfile) } returns profile()
        every { assets.resolve(any()) } answers { descriptor(firstArg<PresentationAssetRef>()) }
        every { links.resolve(configuration.publicationId, listOf(body.content)) } returns emptyMap()

        val page = (service.getRoot() as BlogPageLookupResult.Found).page

        assertThat(page.root).isTrue()
        assertThat(page.firstPublishedAt).isNull()
        assertThat(page.lastModifiedAt).isEqualTo(modifiedAt)
    }

    @Test
    fun `omits missing unpublished and out of scope layout fragments without failing the page`() {
        val body = post("body")
        val unpublishedHeader = post("unpublished-header")
        val headerDocument = sourceDocument("unpublished-header")
        val footerDocument = sourceDocument("missing-footer")
        val configuration =
            configuration(
                headerDocument = headerDocument,
                footerDocument = footerDocument,
            )
        val profile = profile()
        every { publishedPosts.get(body.id) } returns found(body)
        every { siteConfigurations.findCurrent() } returns configuration
        every { profiles.findProfile(configuration.presentationProfile) } returns profile
        every { postRepository.findBinding(headerDocument) } returns binding(unpublishedHeader, headerDocument)
        every { publishedPosts.get(unpublishedHeader.id) } returns PostLookupResult.NotFound
        every { postRepository.findBinding(footerDocument) } returns null
        every { assets.resolve(any()) } answers { descriptor(firstArg<PresentationAssetRef>()) }
        every { links.resolve(configuration.publicationId, listOf(body.content)) } returns emptyMap()

        val result = service.get(body.id)

        assertThat(result).isInstanceOf(BlogPageLookupResult.Found::class.java)
        val page = (result as BlogPageLookupResult.Found).page
        assertThat(page.header).isNull()
        assertThat(page.footer).isNull()
    }

    @Test
    fun `preserves unavailable body result`() {
        val bodyPostId = PostId(UUID.randomUUID())
        every { publishedPosts.get(bodyPostId) } returns PostLookupResult.ContentUnavailable

        assertThat(service.get(bodyPostId)).isEqualTo(BlogPageLookupResult.ContentUnavailable)
    }

    @Test
    fun `returns content unavailable when a referenced presentation asset is not trusted`() {
        val body = post("body")
        val configuration = configuration()
        val profile = profile()
        every { publishedPosts.get(body.id) } returns found(body)
        every { siteConfigurations.findCurrent() } returns configuration
        every { profiles.findProfile(configuration.presentationProfile) } returns profile
        every { assets.resolve(profile.assets.styleSheets.single()) } returns null

        assertThat(service.get(body.id)).isEqualTo(BlogPageLookupResult.ContentUnavailable)
    }

    private fun found(post: Post): PostLookupResult = PostLookupResult.Found(post, publishedAt, modifiedAt, root = false)

    private fun configuration(
        headerDocument: SourceDocumentRef? = null,
        footerDocument: SourceDocumentRef? = null,
    ): SiteConfiguration =
        SiteConfiguration.restore(
            publicationId = PublicationId(UUID.randomUUID()),
            documents = SiteDocuments(root = sourceDocument("root"), header = headerDocument, footer = footerDocument),
            metadata = SiteMetadata("Blog", null, "ko-KR", asset("favicon")),
            presentationProfile = PresentationProfileRef(PresentationProfileId(UUID.randomUUID()), 1),
        )

    private fun profile(): PresentationProfile =
        PresentationProfile.restore(
            reference = PresentationProfileRef(PresentationProfileId(UUID.randomUUID()), 1),
            key = PresentationProfileKey("default"),
            tokens = PresentationTokens(),
            assets = PresentationAssets(styleSheets = listOf(asset("style")), scripts = listOf(asset("script"))),
        )

    private fun binding(
        post: Post,
        sourceDocument: SourceDocumentRef,
    ): PostSourceBinding = PostSourceBinding(post.id, sourceDocument)

    private fun post(externalId: String): Post = Post.restore(PostId(UUID.randomUUID()), externalId, BlockTree(emptyList()))

    private fun sourceDocument(externalId: String): SourceDocumentRef = SourceDocumentRef(SourceId("notion"), externalId)

    private fun asset(key: String): PresentationAssetRef = PresentationAssetRef(key, 1, "sha256-$key")

    private fun descriptor(asset: PresentationAssetRef): PresentationAssetDescriptor =
        PresentationAssetDescriptor(
            publicPath = "/assets/${asset.key}",
            mediaType = "text/plain",
            integrity = asset.integrity,
        )
}
