package xyz.robinjoon.notionblog.blog.domain.post.block.content

sealed interface DataViewContent : BlockContent {
    val data: DataSet

    data class Table(
        override val data: DataSet,
        val options: DataTableOptions = DataTableOptions(),
    ) : DataViewContent {
        init {
            require(options.frozenColumns <= data.columns.size) { "frozen column count must not exceed the column count" }
        }
    }

    data class ListView(
        override val data: DataSet,
    ) : DataViewContent

    data class Gallery(
        override val data: DataSet,
        val options: DataGalleryOptions = DataGalleryOptions(),
    ) : DataViewContent
}
