package xyz.robinjoon.notionblog.blog.domain.post.block.content

data class UnsupportedBlockContent(
    val blockType: String,
    val unavailable: Boolean = false,
) : BlockContent {
    init {
        require(blockType.isNotBlank()) { "unsupported block type must not be blank" }
    }
}
