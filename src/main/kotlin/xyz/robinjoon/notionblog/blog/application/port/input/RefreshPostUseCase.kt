package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.RefreshPostResult
import xyz.robinjoon.notionblog.blog.domain.post.PostId

interface RefreshPostUseCase {
    fun refresh(postId: PostId): RefreshPostResult
}
