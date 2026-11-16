package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.post.PostId

@Transactional(readOnly = true)
class MigrationPostQueryService(
    private val posts: PostRepository,
) {
    @Transactional(readOnly = true)
    fun existing(sourceDocument: SourceDocumentRef): PostId? = posts.findBinding(sourceDocument)?.postId
}
