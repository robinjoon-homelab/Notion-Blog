package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.PublicPostLookupResult
import xyz.robinjoon.notionblog.blog.domain.post.PostId

interface GetPublicPostUseCase {
    fun get(postId: PostId): PublicPostLookupResult
}
