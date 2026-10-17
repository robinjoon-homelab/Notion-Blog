package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.PublicFeedLookupResult

interface GetPublicFeedUseCase {
    fun get(): PublicFeedLookupResult
}
