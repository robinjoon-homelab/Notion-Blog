package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PostListLookupResult

interface GetPostListUseCase {
    fun get(page: PageQuery): PostListLookupResult
}
