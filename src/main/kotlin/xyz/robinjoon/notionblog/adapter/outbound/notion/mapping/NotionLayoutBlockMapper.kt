package xyz.robinjoon.notionblog.adapter.outbound.notion.mapping

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.style.WidthToken

internal class NotionLayoutBlockMapper(
    private val richText: NotionRichTextMapper,
) {
    fun map(
        type: String,
        payload: JsonNode,
    ): LayoutBlockContent? =
        when (type) {
            "divider" -> {
                LayoutBlockContent.Divider
            }

            "column_list" -> {
                LayoutBlockContent.ColumnList
            }

            "column" -> {
                LayoutBlockContent.Column(payload.optionalDouble("width_ratio")?.let(::WidthToken))
            }

            "tab" -> {
                LayoutBlockContent.TabContainer
            }

            "table" -> {
                LayoutBlockContent.Table(
                    width = payload.requiredPositiveInt("table_width"),
                    hasColumnHeader = payload.requiredBoolean("has_column_header"),
                    hasRowHeader = payload.requiredBoolean("has_row_header"),
                )
            }

            "table_row" -> {
                LayoutBlockContent.TableRow(payload.requiredArray("cells").map(::cell))
            }

            else -> {
                null
            }
        }

    private fun cell(value: JsonNode): List<InlineContent> {
        if (!value.isArray) throw NotionBlockMappingException("table row cells must be arrays")
        return richText.map(value)
    }

    fun normalizeTabChildren(children: List<BlockNode>): List<BlockNode> =
        children.map { child ->
            when (val content = child.content) {
                is TextBlockContent.Paragraph -> {
                    child.copy(
                        content = LayoutBlockContent.TabItem(title = content.richText, icon = null),
                    )
                }

                is LayoutBlockContent.TabItem -> {
                    child
                }

                else -> {
                    throw NotionBlockMappingException("tab children must be paragraphs or normalized tab items")
                }
            }
        }
}
