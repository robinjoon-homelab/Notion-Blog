package xyz.robinjoon.notionblog.adapter.inbound.web

import xyz.robinjoon.notionblog.adapter.inbound.web.view.PostDocumentView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.PostPageView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.PresentationAssetView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.PresentationProfileView
import xyz.robinjoon.notionblog.application.model.BlogPage
import xyz.robinjoon.notionblog.application.model.LinkResolution
import xyz.robinjoon.notionblog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.site.PresentationAssetRef
import java.time.Clock

class PostPageViewAssembler(
    private val clock: Clock,
    private val feedUrl: String? = null,
) {
    fun assemble(page: BlogPage): PostPageView =
        PostPageView(
            language = page.site.metadata.languageTag,
            siteName = page.site.metadata.siteName,
            title = page.post.title,
            description = page.site.metadata.defaultDescription,
            faviconHref =
                page.site.metadata.favicon
                    ?.let { assetView(page.presentationAssets, it, faviconMediaTypes)?.publicPath },
            profile = profileView(page),
            styleSheets = page.presentation.styleSheets.mapNotNull { assetView(page.presentationAssets, it, styleSheetMediaTypes) },
            scripts = page.presentation.scripts.mapNotNull { assetView(page.presentationAssets, it, scriptMediaTypes) },
            header = page.header?.let { documentView(it, page.links, "header-${it.id.value}-") },
            post = documentView(page.post, page.links, ""),
            footer = page.footer?.let { documentView(it, page.links, "footer-${it.id.value}-") },
            feedUrl = feedUrl,
        )

    private fun documentView(
        post: Post,
        links: Map<LinkTarget.SourceDocument, LinkResolution>,
        idPrefix: String,
    ): PostDocumentView = PostDocumentViewAssembler(post, PostContentViewMapper(links, clock), idPrefix).assemble()

    private fun profileView(page: BlogPage): PresentationProfileView =
        PresentationProfileView(
            listOf(
                "notion-color-mode-${page.presentation.tokens.colorMode.name.lowercase()}",
                "notion-content-width-${page.presentation.tokens.contentWidth.name.lowercase()}",
                "notion-density-${page.presentation.tokens.density.name.lowercase()}",
            ),
        )

    private fun assetView(
        assets: Map<PresentationAssetRef, PresentationAssetDescriptor>,
        reference: PresentationAssetRef,
        expectedMediaTypes: Set<String>,
    ): PresentationAssetView? {
        val descriptor = assets[reference] ?: return null
        if (descriptor.integrity != reference.integrity || descriptor.mediaType.lowercase() !in expectedMediaTypes ||
            !descriptor.publicPath.safeAssetPath()
        ) {
            return null
        }
        return PresentationAssetView(descriptor.publicPath, descriptor.mediaType, descriptor.integrity)
    }

    private fun String.safeAssetPath(): Boolean =
        startsWith('/') && !startsWith("//") && !contains('\\') && !contains('\r') && !contains('\n')

    private companion object {
        val styleSheetMediaTypes = setOf("text/css")
        val scriptMediaTypes = setOf("application/javascript", "text/javascript")
        val faviconMediaTypes = setOf("image/x-icon", "image/png", "image/svg+xml", "image/webp")
    }
}
