package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.PostFeedLookupResult

interface GetPostFeedUseCase {
    fun get(): PostFeedLookupResult
}
