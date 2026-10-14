package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfile
import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration

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
