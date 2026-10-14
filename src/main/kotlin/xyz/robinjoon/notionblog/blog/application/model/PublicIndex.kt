package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.site.SiteMetadata
import java.time.Instant

data class PublicIndex(
    val metadata: SiteMetadata,
    val entries: List<PublicIndexEntry>,
)

data class PublicIndexEntry(
    val post: Post,
    val root: Boolean,
    val firstPublishedAt: Instant,
    val lastModifiedAt: Instant,
)
