package xyz.robinjoon.notionblog.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.application.model.BlogPageLookupResult
import xyz.robinjoon.notionblog.application.model.LinkResolution
import xyz.robinjoon.notionblog.application.model.PostLookupResult
import xyz.robinjoon.notionblog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.site.PresentationAssetRef
import xyz.robinjoon.notionblog.domain.site.PresentationProfile
import xyz.robinjoon.notionblog.domain.site.PresentationProfileId
import xyz.robinjoon.notionblog.domain.site.PresentationProfileKey
import xyz.robinjoon.notionblog.domain.site.PresentationProfileRef
import xyz.robinjoon.notionblog.domain.site.PresentationTokens
import xyz.robinjoon.notionblog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.domain.site.SiteMetadata
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.util.UUID

class GetBlogPageServiceTest {
    private val publishedPosts = mockk<GetPublishedPostService>()
    private val postRepository = mockk<PostRepository>()
    private val siteConfigurations = mockk<SiteConfigurationRepository>()
    private val assets = mockk<PresentationAssetCatalog>()
    private val links = mockk<ResolvePostLinksService>()
    private val service = GetBlogPageService(publishedPosts, postRepository, siteConfigurations, assets, links)

    @Test
    fun `builds a page with publishable header footer trusted assets and one combined link resolution`() {
        val body = post("body")
        val header = post("header")
        val footer = post("footer")
        val headerDocument = sourceDocument("header")
        val footerDocument = sourceDocument("footer")
        val configuration = configuration(headerDocument = headerDocument, footerDocument = footerDocument)
        val profile = profile()
        val style = profile.styleSheets.single()
        val script = profile.scripts.single()
        val favicon = requireNotNull(configuration.metadata.favicon)
        every { publishedPosts.get(body.id) } returns PostLookupResult.Found(body)
        every { siteConfigurations.findCurrent() } returns configuration
        every { siteConfigurations.findProfile(configuration.presentationProfile) } returns profile
        every { postRepository.findBinding(headerDocument) } returns binding(header, headerDocument)
        every { postRepository.findBinding(footerDocument) } returns binding(footer, footerDocument)
        every { publishedPosts.get(header.id) } returns PostLookupResult.Found(header)
        every { publishedPosts.get(footer.id) } returns PostLookupResult.Found(footer)
        every { assets.resolve(style) } returns descriptor(style)
        every { assets.resolve(script) } returns descriptor(script)
        every { assets.resolve(favicon) } returns descriptor(favicon)
        every { links.resolve(configuration.publicationId, listOf(body.content, header.content, footer.content)) } returns emptyMap()

        val result = service.get(body.id)

        assertThat(result).isEqualTo(
            BlogPageLookupResult.Found(
                xyz.robinjoon.notionblog.application.model.BlogPage(
                    site = configuration,
                    presentation = profile,
                    presentationAssets = mapOf(style to descriptor(style), script to descriptor(script), favicon to descriptor(favicon)),
                    post = body,
                    header = header,
                    footer = footer,
                    links = emptyMap(),
                ),
            ),
        )
        verify(exactly = 1) { links.resolve(configuration.publicationId, listOf(body.content, header.content, footer.content)) }
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
        every { publishedPosts.get(body.id) } returns PostLookupResult.Found(body)
        every { siteConfigurations.findCurrent() } returns configuration
        every { siteConfigurations.findProfile(configuration.presentationProfile) } returns profile
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
        verify(exactly = 1) { links.resolve(configuration.publicationId, listOf(body.content)) }
    }

    @Test
    fun `preserves unavailable body result`() {
        val bodyPostId = PostId(UUID.randomUUID())
        every { publishedPosts.get(bodyPostId) } returns PostLookupResult.ContentUnavailable

        assertThat(service.get(bodyPostId)).isEqualTo(BlogPageLookupResult.ContentUnavailable)
        verify(exactly = 0) { siteConfigurations.findCurrent() }
    }

    @Test
    fun `returns content unavailable when a referenced presentation asset is not trusted`() {
        val body = post("body")
        val configuration = configuration()
        val profile = profile()
        every { publishedPosts.get(body.id) } returns PostLookupResult.Found(body)
        every { siteConfigurations.findCurrent() } returns configuration
        every { siteConfigurations.findProfile(configuration.presentationProfile) } returns profile
        every { assets.resolve(profile.styleSheets.single()) } returns null

        assertThat(service.get(body.id)).isEqualTo(BlogPageLookupResult.ContentUnavailable)
        verify(exactly = 0) { links.resolve(any(), any()) }
    }

    @Test
    fun `owns read only transactions for root and targeted reads`() {
        val methods = GetBlogPageService::class.java.methods.filter { it.name == "get" || it.name == "getRoot" }

        assertThat(methods).allSatisfy { method ->
            assertThat(method.getAnnotation(Transactional::class.java)?.readOnly).isTrue()
        }
    }

    @Test
    fun `does not declare a component scanning stereotype`() {
        assertThat(GetBlogPageService::class.java.isAnnotationPresent(Service::class.java)).isFalse()
    }

    private fun configuration(
        headerDocument: SourceDocumentRef? = null,
        footerDocument: SourceDocumentRef? = null,
    ): SiteConfiguration =
        SiteConfiguration(
            publicationId = PublicationId(UUID.randomUUID()),
            rootDocument = sourceDocument("root"),
            headerDocument = headerDocument,
            footerDocument = footerDocument,
            metadata = SiteMetadata("Blog", null, "ko-KR", asset("favicon")),
            presentationProfile = PresentationProfileRef(PresentationProfileId(UUID.randomUUID()), 1),
        )

    private fun profile(): PresentationProfile =
        PresentationProfile(
            id = PresentationProfileId(UUID.randomUUID()),
            key = PresentationProfileKey("default"),
            version = 1,
            tokens = PresentationTokens(),
            styleSheets = listOf(asset("style")),
            scripts = listOf(asset("script")),
        )

    private fun binding(
        post: Post,
        sourceDocument: SourceDocumentRef,
    ): PostSourceBinding = PostSourceBinding(post.id, sourceDocument)

    private fun post(externalId: String): Post = Post(PostId(UUID.randomUUID()), externalId, BlockTree(emptyList()))

    private fun sourceDocument(externalId: String): SourceDocumentRef = SourceDocumentRef(SourceId("notion"), externalId)

    private fun asset(key: String): PresentationAssetRef = PresentationAssetRef(key, 1, "sha256-$key")

    private fun descriptor(asset: PresentationAssetRef): PresentationAssetDescriptor =
        PresentationAssetDescriptor(
            publicPath = "/assets/${asset.key}",
            mediaType = "text/plain",
            integrity = asset.integrity,
        )
}
