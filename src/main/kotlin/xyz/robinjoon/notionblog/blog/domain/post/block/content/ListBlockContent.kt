package xyz.robinjoon.notionblog.blog.domain.post.block.content

import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent

sealed interface ListBlockContent : BlockContent {
    val richText: List<InlineContent>

    data class BulletedItem(
        override val richText: List<InlineContent>,
    ) : ListBlockContent

    data class NumberedItem(
        override val richText: List<InlineContent>,
        val startNumber: Int = 1,
        val displayFormat: NumberedListFormat = NumberedListFormat.DECIMAL,
        val startsNewList: Boolean = false,
    ) : ListBlockContent {
        init {
            require(startNumber > 0) { "numbered list start number must be positive" }
        }
    }

    data class ToDoItem(
        override val richText: List<InlineContent>,
        val checked: Boolean,
    ) : ListBlockContent
}

enum class NumberedListFormat {
    DECIMAL,
    LOWER_ALPHA,
    UPPER_ALPHA,
    LOWER_ROMAN,
    UPPER_ROMAN,
}
