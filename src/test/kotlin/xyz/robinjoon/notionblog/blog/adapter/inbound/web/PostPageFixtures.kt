package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.application.model.BlogPage
import xyz.robinjoon.notionblog.blog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
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

internal object PostPageFixtures {
    val postId = PostId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    val publishedAt: Instant = Instant.parse("2026-10-01T09:00:00Z")
    val modifiedAt: Instant = Instant.parse("2026-10-02T10:30:15.123Z")
    val metadata = SiteMetadata("My blog", "Site description", "ko-KR", null)

    fun page(
        title: String = "Article title",
        body: String = "Article body text",
        root: Boolean = false,
        firstPublishedAt: Instant? = publishedAt,
        metadata: SiteMetadata = this.metadata,
    ): BlogPage {
        val style = PresentationAssetRef("style", 1, "sha256-style")
        val profile =
            PresentationProfile.restore(
                reference = PresentationProfileRef(PresentationProfileId(UUID.fromString("00000000-0000-0000-0000-000000000001")), 1),
                key = PresentationProfileKey("default"),
                tokens = PresentationTokens(),
                assets = PresentationAssets(styleSheets = listOf(style), scripts = emptyList()),
            )
        val blocks =
            if (body.isEmpty()) {
                emptyList()
            } else {
                listOf(BlockNode(BlockId("p"), TextBlockContent.Paragraph(listOf(InlineContent.Text(body)))))
            }
        return BlogPage(
            site =
                SiteConfiguration.restore(
                    publicationId = PublicationId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                    documents = SiteDocuments(root = SourceDocumentRef(SourceId("notion"), "root"), header = null, footer = null),
                    metadata = metadata,
                    presentationProfile = profile.reference,
                ),
            presentation = profile,
            presentationAssets = mapOf(style to PresentationAssetDescriptor("/presentation/notion.css", "text/css", style.integrity)),
            post = Post.restore(postId, title, BlockTree(blocks)),
            header = null,
            footer = null,
            links = emptyMap(),
            firstPublishedAt = firstPublishedAt,
            lastModifiedAt = modifiedAt,
            root = root,
        )
    }
}
