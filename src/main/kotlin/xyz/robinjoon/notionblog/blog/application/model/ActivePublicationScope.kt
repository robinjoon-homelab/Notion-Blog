package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.site.SiteConfiguration

// The validated active publication scope shared by public listings. Layout documents are current header and footer
// bindings; they may include the root when the site points a layout slot at the root document.
data class ActivePublicationScope(
    val site: SiteConfiguration,
    val publicationId: PublicationId,
    val rootPostId: PostId,
    val layoutPostIds: Set<PostId>,
)
