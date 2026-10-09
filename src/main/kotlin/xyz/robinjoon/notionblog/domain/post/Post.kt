package xyz.robinjoon.notionblog.domain.post

import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import java.util.UUID

@JvmInline
value class PostId(
    val value: UUID,
)

data class Post(
    val id: PostId,
    val title: String,
    val content: BlockTree,
) {
    init {
        require(title.isNotBlank()) { "post title must not be blank" }
    }
}
