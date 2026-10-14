package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId

data class PublicationSynchronizationContext(
    val publicationId: PublicationId,
    val rootDocument: SourceDocumentRef,
)

data class PostSynchronizationContext(
    val publicationId: PublicationId,
    val postId: PostId,
    val sourceDocument: SourceDocumentRef,
    val activeDirectChildren: Set<SourceDocumentRef>,
)
