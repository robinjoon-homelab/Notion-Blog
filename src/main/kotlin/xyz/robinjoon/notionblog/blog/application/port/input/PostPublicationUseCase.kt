package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.ArchivePostResult
import xyz.robinjoon.notionblog.blog.application.model.PublishPostCommand
import xyz.robinjoon.notionblog.blog.application.model.PublishPostResult
import xyz.robinjoon.notionblog.blog.application.model.RestorePostResult
import xyz.robinjoon.notionblog.blog.application.model.UnpublishPostResult
import xyz.robinjoon.notionblog.blog.domain.post.PostId

// 독자가 보는 내용을 바꾸는 유일한 명령이다. 어느 명령도 원본을 호출하지 않는다.
interface PostPublicationUseCase {
    fun publish(command: PublishPostCommand): PublishPostResult

    fun unpublish(postId: PostId): UnpublishPostResult

    fun archive(postId: PostId): ArchivePostResult

    fun restore(postId: PostId): RestorePostResult
}
