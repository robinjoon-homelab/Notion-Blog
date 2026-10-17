package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.HomePageQuery
import xyz.robinjoon.notionblog.blog.application.model.PostListLookupResult

interface GetPostListUseCase {
    fun get(query: HomePageQuery): PostListLookupResult
}
