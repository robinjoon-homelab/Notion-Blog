package xyz.robinjoon.notionblog.blog.domain.post.block.content

import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent

sealed interface ReusableBlockContent : BlockContent {
    data class Synchronized(
        val origin: SynchronizedBlockOrigin?,
    ) : ReusableBlockContent

    data class Template(
        val title: List<InlineContent> = emptyList(),
    ) : ReusableBlockContent
}

data class SynchronizedBlockOrigin(
    val document: SourceDocumentRef,
    val blockExternalId: String,
) {
    init {
        require(blockExternalId.isNotBlank()) { "synchronized block origin id must not be blank" }
    }
}
