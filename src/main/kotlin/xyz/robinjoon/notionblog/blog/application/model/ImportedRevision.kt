package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.sourceHostedMedia
import java.time.Instant

data class ImportedRevision(
    val sourceDocument: SourceDocumentRef,
    val properties: PostProperties,
    val sourceRevision: SourceRevision,
    val content: BlockTree,
    val authoredAt: Instant? = null,
) {
    fun sourceHostedMedia(): Set<MediaSource.SourceHosted> = sourceHostedMedia(properties, content)
}
