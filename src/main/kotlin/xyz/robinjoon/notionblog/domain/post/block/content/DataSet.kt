package xyz.robinjoon.notionblog.domain.post.block.content

import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.post.block.media.MediaSource

data class DataSet(
    val title: String,
    val columns: List<DataColumn>,
    val rows: List<DataRow>,
    val titleColumnIndex: Int? = null,
) {
    init {
        require(title.isNotBlank()) { "data set title must not be blank" }
        require(columns.isNotEmpty()) { "data set columns must not be empty" }
        require(rows.all { it.cells.size == columns.size }) { "data set rows must contain exactly the column count" }
        require(
            titleColumnIndex == null || titleColumnIndex in columns.indices,
        ) { "data set title column index must be within the columns" }
    }
}

data class DataColumn(
    val name: String,
    val widthPixels: Int? = null,
    val wrap: Boolean? = null,
) {
    init {
        require(name.isNotBlank()) { "data column name must not be blank" }
        require(widthPixels == null || widthPixels >= 0) { "data column width must not be negative" }
    }
}

data class DataRow(
    val cells: List<List<InlineContent>>,
    val link: LinkTarget? = null,
    val icon: BlockIcon? = null,
    val cover: MediaSource? = null,
)
