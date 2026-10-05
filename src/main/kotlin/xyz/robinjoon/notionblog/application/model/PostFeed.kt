package xyz.robinjoon.notionblog.application.model

import xyz.robinjoon.notionblog.domain.site.SiteMetadata

data class PostFeed(
    val metadata: SiteMetadata,
    val entries: List<PostFeedEntry>,
)
