package xyz.robinjoon.notionblog.application.port.input

import xyz.robinjoon.notionblog.application.model.PostFeedLookupResult

interface GetPostFeedUseCase {
    fun get(): PostFeedLookupResult
}
