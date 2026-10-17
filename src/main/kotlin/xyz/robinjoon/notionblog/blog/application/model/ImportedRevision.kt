package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.sourceHostedMedia

data class ImportedRevision(
    val sourceDocument: SourceDocumentRef,
    val properties: PostProperties,
    val sourceRevision: SourceRevision,
    val content: BlockTree,
) {
    fun sourceHostedMedia(): Set<MediaSource.SourceHosted> = sourceHostedMedia(properties, content)
}
