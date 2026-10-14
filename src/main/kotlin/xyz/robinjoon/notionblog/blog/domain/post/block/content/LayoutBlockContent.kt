package xyz.robinjoon.notionblog.blog.domain.post.block.content

import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.style.WidthToken

sealed interface LayoutBlockContent : BlockContent {
    data object Divider : LayoutBlockContent

    data object ColumnList : LayoutBlockContent

    data class Column(
        val width: WidthToken?,
    ) : LayoutBlockContent

    data object TabContainer : LayoutBlockContent

    data class TabItem(
        val title: List<InlineContent>,
        val icon: BlockIcon?,
    ) : LayoutBlockContent

    data class Table(
        val width: Int,
        val hasColumnHeader: Boolean = false,
        val hasRowHeader: Boolean = false,
    ) : LayoutBlockContent {
        init {
            require(width > 0) { "table width must be positive" }
        }
    }

    data class TableRow(
        val cells: List<List<InlineContent>>,
    ) : LayoutBlockContent
}
