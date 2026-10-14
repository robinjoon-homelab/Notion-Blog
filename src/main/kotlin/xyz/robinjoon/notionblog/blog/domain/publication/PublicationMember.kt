package xyz.robinjoon.notionblog.blog.domain.publication

import xyz.robinjoon.notionblog.blog.domain.post.PostId

data class PublicationMember(
    val revisionId: PublicationRevisionId,
    val postId: PostId,
    val parentPostId: PostId?,
    val depth: Int,
) {
    init {
        require(depth >= 0) { "publication member depth cannot be negative" }
    }
}
