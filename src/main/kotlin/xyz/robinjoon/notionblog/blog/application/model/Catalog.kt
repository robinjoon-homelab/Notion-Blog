package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef

data class CatalogEntry(
    val sourceDocument: SourceDocumentRef,
    val title: String,
)

data class CatalogSynchronization(
    val registered: Int,
)
