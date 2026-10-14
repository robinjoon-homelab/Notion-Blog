package xyz.robinjoon.notionblog.blog.domain.post.block.content

import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget

sealed interface ReferenceBlockContent : BlockContent {
    data class ChildPost(
        val title: String,
        val reference: SourceDocumentRef,
    ) : ReferenceBlockContent {
        init {
            require(title.isNotBlank()) { "child post title must not be blank" }
        }
    }

    data class DocumentLink(
        val reference: SourceDocumentRef,
        val originalUrl: java.net.URI?,
    ) : ReferenceBlockContent

    data class DatabaseLink(
        val reference: SourceDocumentRef,
        val originalUrl: java.net.URI?,
        val title: String? = null,
    ) : ReferenceBlockContent {
        init {
            require(title == null || title.isNotBlank()) { "database link title must not be blank" }
        }
    }

    data class Breadcrumb(
        val items: List<LinkTarget>,
    ) : ReferenceBlockContent

    data object TableOfContents : ReferenceBlockContent
}
