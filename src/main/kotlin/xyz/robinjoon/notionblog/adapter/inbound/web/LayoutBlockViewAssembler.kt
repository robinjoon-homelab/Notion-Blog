package xyz.robinjoon.notionblog.adapter.inbound.web

import xyz.robinjoon.notionblog.adapter.inbound.web.view.BlockStyleView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.BlockView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.ColumnListView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.ColumnView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.ColumnWidthClass
import xyz.robinjoon.notionblog.adapter.inbound.web.view.DividerView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.TabContainerView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.TabItemView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.TableRowView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.TableView
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent

internal class LayoutBlockViewAssembler(
    private val contentMapper: PostContentViewMapper,
) {
    fun assemble(
        content: LayoutBlockContent,
        id: String,
        style: BlockStyleView,
        children: List<BlockView>,
    ): BlockView =
        when (content) {
            LayoutBlockContent.Divider -> {
                DividerView(id, style, children)
            }

            LayoutBlockContent.ColumnList -> {
                ColumnListView(id, children.map { it as ColumnView }, style)
            }

            is LayoutBlockContent.Column -> {
                ColumnView(id, ColumnWidthClass.fromRatio(content.width?.ratio), style, children)
            }

            LayoutBlockContent.TabContainer -> {
                TabContainerView(id, children.map { it as TabItemView }, style)
            }

            is LayoutBlockContent.TabItem -> {
                TabItemView(
                    id,
                    contentMapper.inlineViews(content.title),
                    contentMapper.iconView(content.icon),
                    style,
                    children,
                )
            }

            is LayoutBlockContent.Table -> {
                table(content, id, style, children)
            }

            is LayoutBlockContent.TableRow -> {
                TableRowView(id, content.cells.map(contentMapper::inlineViews), style, children)
            }
        }

    private fun table(
        content: LayoutBlockContent.Table,
        id: String,
        style: BlockStyleView,
        children: List<BlockView>,
    ): TableView =
        TableView(
            id,
            content.hasColumnHeader,
            content.hasRowHeader,
            children.map { it as TableRowView },
            style,
        )
}
