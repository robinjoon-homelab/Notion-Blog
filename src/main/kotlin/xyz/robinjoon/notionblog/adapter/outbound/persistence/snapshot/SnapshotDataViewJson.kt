package xyz.robinjoon.notionblog.adapter.outbound.persistence.snapshot

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.domain.post.block.content.DataColumn
import xyz.robinjoon.notionblog.domain.post.block.content.DataGalleryOptions
import xyz.robinjoon.notionblog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.domain.post.block.content.DataTableOptions
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent

internal fun toJsonDataView(content: DataViewContent): ObjectNode =
    objectNode().apply {
        set("data", toJsonDataSet(content.data))
        when (content) {
            is DataViewContent.Table -> {
                put("layout", "table")
                set("options", toJsonTableOptions(content.options))
            }

            is DataViewContent.ListView -> {
                put("layout", "list")
            }

            is DataViewContent.Gallery -> {
                put("layout", "gallery")
                set("options", toJsonGalleryOptions(content.options))
            }
        }
    }

internal fun fromJsonDataView(content: ObjectNode): DataViewContent {
    val data = fromJsonDataSet(content.requiredObject("data", "data view"))
    return when (val layout = content.requiredText("layout", "data view")) {
        "table" -> DataViewContent.Table(data, fromJsonTableOptions(content.requiredObject("options", "data table view")))
        "list" -> DataViewContent.ListView(data)
        "gallery" -> DataViewContent.Gallery(data, fromJsonGalleryOptions(content.requiredObject("options", "data gallery view")))
        else -> throw IllegalArgumentException("unsupported data view layout: $layout")
    }
}

internal fun fromJsonLegacyDataTable(content: ObjectNode): DataViewContent.Table =
    DataViewContent.Table(
        DataSet(
            title = content.requiredText("title", "data_table"),
            columns = content.requiredArray("columns", "data_table").toList().map(::fromJsonLegacyColumn),
            rows =
                content.requiredArray("rows", "data_table").toList().map { row ->
                    DataRow(fromJsonDataCells(row.requireObject("data_table row")))
                },
        ),
    )

private fun fromJsonLegacyColumn(column: JsonNode): DataColumn {
    require(column.isString) { "data_table column must be a string" }
    return DataColumn(column.asString())
}

private fun toJsonDataSet(data: DataSet): ObjectNode =
    objectNode().apply {
        put("title", data.title)
        set("titleColumnIndex", nullableJson(data.titleColumnIndex) { JsonNodeFactory.instance.numberNode(it) })
        set("columns", jsonArray(data.columns, ::toJsonDataColumn))
        set("rows", jsonArray(data.rows, ::toJsonDataRow))
    }

private fun toJsonDataColumn(column: DataColumn): ObjectNode =
    objectNode().apply {
        put("name", column.name)
        set("widthPixels", nullableJson(column.widthPixels) { JsonNodeFactory.instance.numberNode(it) })
        set("wrap", nullableJson(column.wrap) { JsonNodeFactory.instance.booleanNode(it) })
    }

private fun toJsonDataRow(row: DataRow): ObjectNode =
    objectNode().apply {
        set("cells", jsonArray(row.cells, ::toJsonInlineList))
        set("link", nullableJson(row.link, ::toJsonLink))
        set("icon", nullableJson(row.icon, ::toJsonIcon))
        set("cover", nullableJson(row.cover, ::toJsonMediaSource))
    }

private fun fromJsonDataSet(data: ObjectNode): DataSet =
    DataSet(
        title = data.requiredText("title", "data set"),
        columns = data.requiredArray("columns", "data set").toList().map(::fromJsonDataColumn),
        rows = data.requiredArray("rows", "data set").toList().map(::fromJsonDataRow),
        titleColumnIndex = data.optionalInt("titleColumnIndex"),
    )

private fun fromJsonDataColumn(node: JsonNode): DataColumn {
    val column = node.requireObject("data column")
    return DataColumn(
        name = column.requiredText("name", "data column"),
        widthPixels = column.optionalInt("widthPixels"),
        wrap = column.optionalBoolean("wrap"),
    )
}

private fun fromJsonDataRow(node: JsonNode): DataRow {
    val row = node.requireObject("data row")
    return DataRow(
        cells = fromJsonDataCells(row),
        link = row.optionalObject("link")?.let(::fromJsonLink),
        icon = row.optionalObject("icon")?.let(::fromJsonIcon),
        cover = row.optionalObject("cover")?.let(::fromJsonMediaSource),
    )
}

private fun fromJsonDataCells(row: ObjectNode): List<List<InlineContent>> =
    row.requiredArray("cells", "data row").toList().map { cell ->
        cell.requireArray("data cell").toList().map(::fromJsonInline)
    }

private fun toJsonTableOptions(options: DataTableOptions): ObjectNode =
    objectNode().apply {
        put("wrapCells", options.wrapCells)
        put("frozenColumns", options.frozenColumns)
        put("showVerticalLines", options.showVerticalLines)
    }

private fun fromJsonTableOptions(options: ObjectNode): DataTableOptions =
    DataTableOptions(
        wrapCells = options.requiredBoolean("wrapCells", "data table options"),
        frozenColumns = options.requiredInt("frozenColumns", "data table options"),
        showVerticalLines = options.requiredBoolean("showVerticalLines", "data table options"),
    )

private fun toJsonGalleryOptions(options: DataGalleryOptions): ObjectNode =
    objectNode().apply {
        put("size", options.size.logicalValue())
        put("aspect", options.aspect.logicalValue())
        put("layout", options.layout.logicalValue())
    }

private fun fromJsonGalleryOptions(options: ObjectNode): DataGalleryOptions =
    DataGalleryOptions(
        size = options.requiredEnum("size", "data gallery options", ::dataCardSize),
        aspect = options.requiredEnum("aspect", "data gallery options", ::dataCoverAspect),
        layout = options.requiredEnum("layout", "data gallery options", ::dataCardLayout),
    )
