package xyz.robinjoon.notionblog.blog.domain.sync

import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId

sealed interface SyncTarget {
    data object SiteConfiguration : SyncTarget

    data class Publication(
        val publicationId: PublicationId,
    ) : SyncTarget

    data class Post(
        val postId: PostId,
    ) : SyncTarget
}
