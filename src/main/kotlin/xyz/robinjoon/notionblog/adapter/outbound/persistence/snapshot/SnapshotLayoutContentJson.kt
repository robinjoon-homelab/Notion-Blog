package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.style.WidthToken

internal val layoutBlockKinds = setOf("divider", "column_list", "column", "tab_container", "tab_item", "table", "table_row")

internal fun kindOfLayout(content: LayoutBlockContent): String =
    when (content) {
        LayoutBlockContent.Divider -> "divider"
        LayoutBlockContent.ColumnList -> "column_list"
        is LayoutBlockContent.Column -> "column"
        LayoutBlockContent.TabContainer -> "tab_container"
        is LayoutBlockContent.TabItem -> "tab_item"
        is LayoutBlockContent.Table -> "table"
        is LayoutBlockContent.TableRow -> "table_row"
    }

internal fun toJsonLayout(content: LayoutBlockContent): ObjectNode =
    when (content) {
        LayoutBlockContent.Divider -> objectNode()
        LayoutBlockContent.ColumnList -> objectNode()
        is LayoutBlockContent.Column -> encodeColumn(content)
        LayoutBlockContent.TabContainer -> objectNode()
        is LayoutBlockContent.TabItem -> encodeTabItem(content)
        is LayoutBlockContent.Table -> encodeTable(content)
        is LayoutBlockContent.TableRow -> encodeTableRow(content)
    }

internal fun fromJsonLayout(
    kind: String,
    content: ObjectNode,
): LayoutBlockContent =
    when (kind) {
        "divider" -> {
            LayoutBlockContent.Divider
        }

        "column_list" -> {
            LayoutBlockContent.ColumnList
        }

        "column" -> {
            LayoutBlockContent.Column(content.optionalDouble("width")?.let(::WidthToken))
        }

        "tab_container" -> {
            LayoutBlockContent.TabContainer
        }

        "tab_item" -> {
            LayoutBlockContent.TabItem(
                content.requiredInlineList("title", kind),
                content.optionalObject("icon")?.let(::fromJsonIcon),
            )
        }

        "table" -> {
            decodeTable(kind, content)
        }

        "table_row" -> {
            decodeTableRow(kind, content)
        }

        else -> {
            throw IllegalArgumentException("unsupported layout block kind: $kind")
        }
    }

private fun encodeColumn(content: LayoutBlockContent.Column): ObjectNode =
    objectNode().apply {
        set("width", nullableJson(content.width) { numberNode(it.ratio) })
    }

private fun encodeTabItem(content: LayoutBlockContent.TabItem): ObjectNode =
    objectNode().apply {
        set("title", toJsonInlineList(content.title))
        set("icon", nullableJson(content.icon, ::toJsonIcon))
    }

private fun encodeTable(content: LayoutBlockContent.Table): ObjectNode =
    objectNode().apply {
        put("width", content.width)
        put("hasColumnHeader", content.hasColumnHeader)
        put("hasRowHeader", content.hasRowHeader)
    }

private fun encodeTableRow(content: LayoutBlockContent.TableRow): ObjectNode =
    objectNode().apply {
        set("cells", jsonArray(content.cells, ::toJsonInlineList))
    }

private fun decodeTable(
    kind: String,
    content: ObjectNode,
): LayoutBlockContent.Table =
    LayoutBlockContent.Table(
        content.requiredInt("width", kind),
        content.requiredBoolean("hasColumnHeader", kind),
        content.requiredBoolean("hasRowHeader", kind),
    )

private fun decodeTableRow(
    kind: String,
    content: ObjectNode,
): LayoutBlockContent.TableRow =
    LayoutBlockContent.TableRow(
        content.requiredArray("cells", kind).toList().map {
            it.requireArray("$kind cell").toList().map(::fromJsonInline)
        },
    )
