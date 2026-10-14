package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.BlogPageLookupResult
import xyz.robinjoon.notionblog.blog.domain.post.PostId

interface GetBlogPageUseCase {
    fun getRoot(): BlogPageLookupResult

    fun get(postId: PostId): BlogPageLookupResult
}
