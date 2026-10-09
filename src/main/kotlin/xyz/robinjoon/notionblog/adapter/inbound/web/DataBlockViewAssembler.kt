package xyz.robinjoon.notionblog.adapter.inbound.web

import xyz.robinjoon.notionblog.adapter.inbound.web.view.BlockStyleView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.BlockView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.DataColumnWidthClass
import xyz.robinjoon.notionblog.adapter.inbound.web.view.DataEntryView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.DataGalleryView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.DataListView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.DataPropertyView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.DataTableColumnView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.DataTableRowView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.DataTableView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.ListView
import xyz.robinjoon.notionblog.domain.post.block.content.DataCardLayout
import xyz.robinjoon.notionblog.domain.post.block.content.DataCardSize
import xyz.robinjoon.notionblog.domain.post.block.content.DataCoverAspect
import xyz.robinjoon.notionblog.domain.post.block.content.DataGalleryOptions
import xyz.robinjoon.notionblog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent

internal class DataBlockViewAssembler(
    private val contentMapper: PostContentViewMapper,
) {
    fun assemble(
        content: DataViewContent,
        id: String,
        style: BlockStyleView,
    ): BlockView =
        when (content) {
            is DataViewContent.Table -> {
                table(content, id, style)
            }

            is DataViewContent.ListView -> {
                DataListView(id, content.data.title, entries(content.data), style)
            }

            is DataViewContent.Gallery -> {
                DataGalleryView(
                    id,
                    content.data.title,
                    entries(content.data),
                    galleryClasses(content.options),
                    style,
                )
            }
        }

    private fun table(
        content: DataViewContent.Table,
        id: String,
        style: BlockStyleView,
    ): DataTableView =
        DataTableView(
            id,
            content.data.title,
            content.data.columns.mapIndexed { index, column ->
                DataTableColumnView(
                    column.name,
                    DataColumnWidthClass.fromPixels(column.widthPixels),
                    column.wrap ?: content.options.wrapCells,
                    index < content.options.frozenColumns,
                )
            },
            content.data.rows.map { row -> tableRow(row, content.data.titleColumnIndex) },
            content.data.titleColumnIndex,
            content.options.frozenColumns,
            content.options.showVerticalLines,
            style,
        )

    private fun tableRow(
        row: DataRow,
        titleColumnIndex: Int?,
    ): DataTableRowView {
        val rowLink = row.link?.let(contentMapper::linkView)
        val cells =
            row.cells.mapIndexed { index, cell ->
                val inlines = contentMapper.inlineViews(cell)
                if (index == titleColumnIndex && rowLink != null) inlines.map { contentMapper.withLink(it, rowLink) } else inlines
            }
        return DataTableRowView(cells, contentMapper.iconView(row.icon))
    }

    private fun entries(data: DataSet): List<DataEntryView> = data.rows.map { entry(data, it) }

    private fun entry(
        data: DataSet,
        row: DataRow,
    ): DataEntryView {
        val title =
            data.titleColumnIndex
                ?.let { row.cells[it] }
                .orEmpty()
                .takeIf { contentMapper.plainText(it).isNotBlank() } ?: listOf(InlineContent.Text("Untitled"))
        val rowLink = row.link?.let(contentMapper::linkView)
        return DataEntryView(
            contentMapper.inlineViews(title).map { if (rowLink != null) contentMapper.withLink(it, null) else it },
            contentMapper.plainText(title),
            rowLink,
            contentMapper.iconView(row.icon),
            row.cover?.let(contentMapper::mediaUrl),
            data.columns.mapIndexedNotNull { index, column ->
                if (index == data.titleColumnIndex) null else DataPropertyView(column.name, contentMapper.inlineViews(row.cells[index]))
            },
        )
    }

    private fun galleryClasses(options: DataGalleryOptions): List<String> =
        listOf(
            when (options.size) {
                DataCardSize.SMALL -> "notion-data-gallery-small"
                DataCardSize.MEDIUM -> "notion-data-gallery-medium"
                DataCardSize.LARGE -> "notion-data-gallery-large"
            },
            when (options.aspect) {
                DataCoverAspect.CONTAIN -> "notion-data-gallery-contain"
                DataCoverAspect.COVER -> "notion-data-gallery-cover"
            },
            when (options.layout) {
                DataCardLayout.LIST -> "notion-data-gallery-list"
                DataCardLayout.COMPACT -> "notion-data-gallery-compact"
            },
        )
}
