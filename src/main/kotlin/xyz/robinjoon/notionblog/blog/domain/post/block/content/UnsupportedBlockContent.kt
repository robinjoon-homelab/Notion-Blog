package xyz.robinjoon.notionblog.blog.domain.post.block.content

data class UnsupportedBlockContent(
    val blockType: String,
) : BlockContent {
    init {
        require(blockType.isNotBlank()) { "unsupported block type must not be blank" }
    }
}
