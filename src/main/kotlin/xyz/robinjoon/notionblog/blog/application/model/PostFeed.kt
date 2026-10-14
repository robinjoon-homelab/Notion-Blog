package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.site.SiteMetadata

data class PostFeed(
    val metadata: SiteMetadata,
    val entries: List<PostFeedEntry>,
)
