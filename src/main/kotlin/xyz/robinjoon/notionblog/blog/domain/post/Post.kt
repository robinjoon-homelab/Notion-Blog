package xyz.robinjoon.notionblog.blog.domain.post

import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.AggregateRoot
import java.util.UUID

@JvmInline
value class PostId(
    val value: UUID,
)

// The renderable snapshot of one source document. Its binding, availability, and first publication time are stored
// with it by the post repository, so the snapshot and the PUBLISHED transition commit together.
@AggregateRoot
@ConsistentCopyVisibility
data class Post private constructor(
    val id: PostId,
    val title: String,
    val content: BlockTree,
) {
    init {
        require(title.isNotBlank()) { "post title must not be blank" }
    }

    companion object {
        fun create(
            id: PostId,
            title: String,
            content: BlockTree,
        ): Post = Post(id, title, content)

        // Restoration validates the stored snapshot without normalizing it, so a read never rewrites data.
        fun restore(
            id: PostId,
            title: String,
            content: BlockTree,
        ): Post = Post(id, title, content)
    }
}
