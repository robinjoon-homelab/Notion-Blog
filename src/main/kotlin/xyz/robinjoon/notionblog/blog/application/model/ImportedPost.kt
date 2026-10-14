package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree

data class ImportedPost(
    val sourceDocument: SourceDocumentRef,
    val title: String,
    val publicationStatus: ImportedPublicationStatus,
    val sourceRevision: SourceRevision,
    val content: BlockTree,
    val containedChildren: List<SourceDocumentRef>,
)

enum class ImportedPublicationStatus {
    PUBLISHED,
    UNPUBLISHED,
}
