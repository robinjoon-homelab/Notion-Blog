package xyz.robinjoon.notionblog.support.fixture

import xyz.robinjoon.notionblog.blog.application.model.ContentReferences
import xyz.robinjoon.notionblog.blog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.blog.application.model.RevisionMedia
import xyz.robinjoon.notionblog.blog.application.model.SiteChrome
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
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
import java.util.UUID

object SiteFixtures {
    val style = PresentationAssetRef("notion-core", 1, "sha384-core")

    val profile: PresentationProfile =
        PresentationProfile.restore(
            reference = PresentationProfileRef(PresentationProfileId(UUID.fromString("00000000-0000-0000-0000-000000000020")), 1),
            key = PresentationProfileKey("notion-default"),
            tokens = PresentationTokens(),
            assets = PresentationAssets(styleSheets = listOf(style), scripts = emptyList()),
        )

    val descriptor = PresentationAssetDescriptor("/presentation/notion.css", "text/css", "sha384-core")

    fun site(documents: SiteDocuments = SiteDocuments(null, null, null)): SiteConfiguration =
        SiteConfiguration.restore(
            publicationId = PublicationId(UUID.fromString("00000000-0000-0000-0000-000000000030")),
            documents = documents,
            metadata = SiteMetadata("Blog", null, "ko-KR", null),
            presentationProfile = profile.reference,
        )

    fun documents(
        root: SourceDocumentRef? = null,
        header: SourceDocumentRef? = null,
        footer: SourceDocumentRef? = null,
    ) = SiteDocuments(root, header, footer)

    fun chrome(header: PostRevision? = null): SiteChrome = SiteChrome(site(), profile, mapOf(style to descriptor), header, null, null)

    fun noReferences(): ContentReferences = ContentReferences(emptyMap(), RevisionMedia.empty)
}
