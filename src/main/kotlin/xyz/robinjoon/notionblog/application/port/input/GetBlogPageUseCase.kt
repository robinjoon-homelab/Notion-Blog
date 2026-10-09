package xyz.robinjoon.notionblog.application.port.input

import xyz.robinjoon.notionblog.application.model.BlogPageLookupResult
import xyz.robinjoon.notionblog.domain.post.PostId

interface GetBlogPageUseCase {
    fun getRoot(): BlogPageLookupResult

    fun get(postId: PostId): BlogPageLookupResult
}
