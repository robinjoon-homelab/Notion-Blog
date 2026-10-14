package xyz.robinjoon.notionblog.blog.domain.post.block.content

data class DataTableOptions(
    val wrapCells: Boolean = true,
    val frozenColumns: Int = 0,
    val showVerticalLines: Boolean = true,
) {
    init {
        require(frozenColumns >= 0) { "frozen column count must not be negative" }
    }
}
