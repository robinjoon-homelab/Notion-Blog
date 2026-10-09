package xyz.robinjoon.notionblog.domain.post

import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef

data class PostSourceBinding(
    val postId: PostId,
    val sourceDocument: SourceDocumentRef,
)
