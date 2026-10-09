package xyz.robinjoon.notionblog.application.model

import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.site.PresentationAssetRef
import xyz.robinjoon.notionblog.domain.site.PresentationProfile
import xyz.robinjoon.notionblog.domain.site.SiteConfiguration

data class BlogPage
    // quality-exception: The complete page projection keeps its seven independently consumed rendering values together.
    @Suppress("LongParameterList")
    constructor(
        val site: SiteConfiguration,
        val presentation: PresentationProfile,
        val presentationAssets: Map<PresentationAssetRef, PresentationAssetDescriptor>,
        val post: Post,
        val header: Post?,
        val footer: Post?,
        val links: Map<LinkTarget.SourceDocument, LinkResolution>,
    )
