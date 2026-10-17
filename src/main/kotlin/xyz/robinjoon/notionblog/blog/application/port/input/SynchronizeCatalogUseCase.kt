package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.CatalogSynchronization

interface SynchronizeCatalogUseCase {
    fun synchronize(): CatalogSynchronization
}
