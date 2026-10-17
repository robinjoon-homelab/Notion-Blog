package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.blog.application.model.PublishedPost
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.SnapshotFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PresentationProfileRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SiteConfigurationRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.blog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssets
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfile
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationTokens
import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.blog.domain.site.SiteDocuments
import xyz.robinjoon.notionblog.blog.domain.site.SiteMetadata
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.fixture.SiteFixtures

class SiteChromeServiceTest {
    private val sites = mockk<SiteConfigurationRepository>()
    private val profiles = mockk<PresentationProfileRepository>()
    private val assets = mockk<PresentationAssetCatalog>()
    private val posts = mockk<PostRepository>()
    private val published = mockk<PublishedPostStore>()
    private val failures = mockk<SnapshotFailureReporter>()
    private val service = SiteChromeService(sites, profiles, assets, posts, published, failures)
    private val root = PostFixtures.document("root")
    private val header = PostFixtures.document("header")
    private val footer = PostFixtures.document("footer")
    private val rootId = PostFixtures.postId(1)
    private val headerId = PostFixtures.postId(2)
    private val footerId = PostFixtures.postId(3)

    @Test
    fun `the chrome is unavailable without a site configuration profile or registered asset`() {
        every { sites.findCurrent() } returns null

        assertThat(service.load()).isNull()

        every { sites.findCurrent() } returns SiteFixtures.site()
        every { profiles.findProfile(SiteFixtures.profile.reference) } returns SiteFixtures.profile
        every { assets.resolve(SiteFixtures.style) } returns null

        assertThat(service.load()).isNull()
    }

    @Test
    fun `site documents appear only when their posts are public`() {
        val headerRevision = PostFixtures.revision(headerId, PostFixtures.revisionId(2))
        givenSite()
        every { published.find(headerId) } returns PublishedPost(headerRevision, PostFixtures.capturedAt)
        every { published.find(footerId) } returns null
        every { published.findPublicPostIds(setOf(rootId)) } returns emptySet()

        val chrome = service.load()

        assertThat(chrome?.header).isEqualTo(headerRevision)
        assertThat(chrome?.footer).isNull()
        assertThat(chrome?.rootPostId).isNull()
        assertThat(chrome?.presentationAssets).containsEntry(SiteFixtures.style, SiteFixtures.descriptor)
    }

    @Test
    fun `an undecodable fragment is left out and the public legacy root is linked`() {
        givenSite()
        every { published.find(headerId) } throws SnapshotContentException("broken")
        every { published.find(footerId) } returns null
        every { published.findPublicPostIds(setOf(rootId)) } returns setOf(rootId)
        every { failures.report(any(), any(), any()) } just Runs

        val chrome = service.load()

        assertThat(chrome?.header).isNull()
        assertThat(chrome?.rootPostId).isEqualTo(rootId)
    }

    @Test
    fun `the chrome resolves the profile scripts and the favicon beside the style sheets`() {
        val script = PresentationAssetRef("notion-toggle", 1, "sha384-toggle")
        val favicon = PresentationAssetRef("favicon", 1, "sha384-icon")
        val scriptDescriptor = PresentationAssetDescriptor("/presentation/toggle.js", "text/javascript", "sha384-toggle")
        val faviconDescriptor = PresentationAssetDescriptor("/presentation/favicon.svg", "image/svg+xml", "sha384-icon")
        val site = siteWith(SiteFixtures.documents(), favicon)
        every { sites.findCurrent() } returns site
        every { profiles.findProfile(SiteFixtures.profile.reference) } returns profileWithScript(script)
        every { assets.resolve(SiteFixtures.style) } returns SiteFixtures.descriptor
        every { assets.resolve(script) } returns scriptDescriptor
        every { assets.resolve(favicon) } returns faviconDescriptor
        every { posts.findBindingsBySourceDocuments(emptySet()) } returns emptyMap()

        val chrome = service.load()

        assertThat(chrome?.presentationAssets).containsOnly(
            entry(SiteFixtures.style, SiteFixtures.descriptor),
            entry(script, scriptDescriptor),
            entry(favicon, faviconDescriptor),
        )
    }

    @Test
    fun `the chrome is unavailable when the favicon or a script is not registered`() {
        val script = PresentationAssetRef("notion-toggle", 1, "sha384-toggle")
        val favicon = PresentationAssetRef("favicon", 1, "sha384-icon")
        every { sites.findCurrent() } returns siteWith(SiteFixtures.documents(), favicon)
        every { profiles.findProfile(SiteFixtures.profile.reference) } returns profileWithScript(script)
        every { assets.resolve(SiteFixtures.style) } returns SiteFixtures.descriptor
        every { assets.resolve(script) } returns SiteFixtures.descriptor
        every { assets.resolve(favicon) } returns null

        assertThat(service.load()).isNull()

        every { assets.resolve(favicon) } returns SiteFixtures.descriptor
        every { assets.resolve(script) } returns null

        assertThat(service.load()).isNull()
    }

    @Test
    fun `site documents without a bound post leave the header the footer and the root out of the chrome`() {
        val site = SiteFixtures.site(SiteFixtures.documents(root, header, footer))
        every { sites.findCurrent() } returns site
        every { profiles.findProfile(SiteFixtures.profile.reference) } returns SiteFixtures.profile
        every { assets.resolve(SiteFixtures.style) } returns SiteFixtures.descriptor
        every { posts.findBindingsBySourceDocuments(site.documents.all) } returns emptyMap()

        val chrome = service.load()

        assertThat(chrome).isNotNull()
        assertThat(chrome?.header).isNull()
        assertThat(chrome?.footer).isNull()
        assertThat(chrome?.rootPostId).isNull()
    }

    @Test
    fun `the root header and footer posts are excluded from the post list`() {
        val site = SiteFixtures.site(SiteFixtures.documents(root, header, footer))
        every { posts.findBindingsBySourceDocuments(site.documents.all) } returns
            mapOf(
                root to PostSourceBinding(rootId, root),
                header to PostSourceBinding(headerId, header),
                footer to PostSourceBinding(footerId, footer),
            )

        assertThat(service.siteDocumentPostIds(site)).containsExactlyInAnyOrder(rootId, headerId, footerId)
    }

    @Test
    fun `only bound site document ids are returned and duplicates are removed`() {
        val site = SiteFixtures.site(SiteFixtures.documents(root, header, footer))
        every { posts.findBindingsBySourceDocuments(site.documents.all) } returns
            mapOf(
                header to PostSourceBinding(headerId, header),
                footer to PostSourceBinding(headerId, footer),
            )

        assertThat(service.siteDocumentPostIds(site)).containsExactly(headerId)
    }

    @Test
    fun `a site without documents excludes no posts`() {
        val site = SiteFixtures.site()
        every { posts.findBindingsBySourceDocuments(emptySet()) } returns emptyMap()

        assertThat(service.siteDocumentPostIds(site)).isEmpty()
    }

    private fun profileWithScript(script: PresentationAssetRef): PresentationProfile =
        PresentationProfile.restore(
            reference = SiteFixtures.profile.reference,
            key = SiteFixtures.profile.key,
            tokens = PresentationTokens(),
            assets = PresentationAssets(styleSheets = listOf(SiteFixtures.style), scripts = listOf(script)),
        )

    private fun siteWith(
        documents: SiteDocuments,
        favicon: PresentationAssetRef,
    ): SiteConfiguration {
        val base = SiteFixtures.site(documents)
        return SiteConfiguration.restore(
            publicationId = base.publicationId,
            documents = documents,
            metadata = SiteMetadata("Blog", null, "ko-KR", favicon),
            presentationProfile = base.presentationProfile,
        )
    }

    private fun givenSite() {
        val site = SiteFixtures.site(SiteFixtures.documents(root, header, footer))
        every { sites.findCurrent() } returns site
        every { profiles.findProfile(SiteFixtures.profile.reference) } returns SiteFixtures.profile
        every { assets.resolve(SiteFixtures.style) } returns SiteFixtures.descriptor
        every { posts.findBindingsBySourceDocuments(site.documents.all) } returns
            mapOf(
                root to PostSourceBinding(rootId, root),
                header to PostSourceBinding(headerId, header),
                footer to PostSourceBinding(footerId, footer),
            )
    }
}
