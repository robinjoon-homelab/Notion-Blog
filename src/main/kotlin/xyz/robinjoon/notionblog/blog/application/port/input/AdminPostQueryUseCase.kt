package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.AdminPostList
import xyz.robinjoon.notionblog.blog.application.model.AdminPostListQuery
import xyz.robinjoon.notionblog.blog.application.model.AdminPostLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PreviewVersion
import xyz.robinjoon.notionblog.blog.domain.post.PostId

interface AdminPostQueryUseCase {
    fun list(query: AdminPostListQuery): AdminPostList

    fun get(
        postId: PostId,
        version: PreviewVersion,
    ): AdminPostLookupResult
}
