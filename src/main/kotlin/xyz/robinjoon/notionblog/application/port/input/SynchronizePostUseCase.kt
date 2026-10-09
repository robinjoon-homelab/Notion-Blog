package xyz.robinjoon.notionblog.application.port.input

import xyz.robinjoon.notionblog.domain.post.PostId

interface SynchronizePostUseCase {
    fun synchronize(postId: PostId)
}
