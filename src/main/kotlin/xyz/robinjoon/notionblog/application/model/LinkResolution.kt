package xyz.robinjoon.notionblog.application.model

import xyz.robinjoon.notionblog.domain.post.PostId
import java.net.URI

sealed interface LinkResolution {
    data class Internal(
        val postId: PostId,
    ) : LinkResolution

    data class External(
        val url: URI,
    ) : LinkResolution

    data object Unlinked : LinkResolution
}
