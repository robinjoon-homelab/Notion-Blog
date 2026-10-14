package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.domain.post.PostId

interface SynchronizePostUseCase {
    fun synchronize(postId: PostId)
}
