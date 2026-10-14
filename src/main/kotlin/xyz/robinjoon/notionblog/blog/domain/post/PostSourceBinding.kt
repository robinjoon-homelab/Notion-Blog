package xyz.robinjoon.notionblog.blog.domain.post

import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef

data class PostSourceBinding(
    val postId: PostId,
    val sourceDocument: SourceDocumentRef,
)
