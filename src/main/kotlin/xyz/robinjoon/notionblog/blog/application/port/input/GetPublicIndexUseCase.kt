package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.PublicIndexLookupResult

interface GetPublicIndexUseCase {
    fun get(): PublicIndexLookupResult
}
