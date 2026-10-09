package xyz.robinjoon.notionblog.adapter.outbound.notion.client

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionDatabaseViewResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionGalleryAspect
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionGalleryCover
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionGalleryLayout
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionGallerySize
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionViewColumn
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionViewConfiguration

internal class NotionViewResponseParser {
    fun parse(response: JsonNode): NotionDatabaseViewResponse {
        require(response.requiredText("object") == "view")
        val parent = response.requiredObject("parent")
        require(parent.requiredText("type") == "database_id")
        val type = response.requiredText("type")
        val configuration = if (type in supportedViewTypes) response.nullableObject("configuration") else null
        require(configuration == null || configuration.requiredText("type") == type)
        return NotionDatabaseViewResponse(
            id = response.requiredText("id"),
            databaseId = parent.requiredText("database_id"),
            name = response.nullableText("name")?.takeIf(String::isNotBlank) ?: "데이터베이스 보기",
            type = type,
            dataSourceId = response.nullableText("data_source_id"),
            columns = configuration?.let(::parseColumns),
            configuration = if (type in supportedViewTypes) parseConfiguration(configuration, type) else null,
        )
    }

    private fun parseColumns(configuration: JsonNode): List<NotionViewColumn>? {
        val properties = configuration.get("properties")?.takeUnless(JsonNode::isNull) ?: return null
        require(properties.isArray)
        return properties.mapNotNull { property ->
            val propertyId = property.requiredText("property_id")
            val name = property.nullableText("property_name")
            val visible = property.get("visible")
            require(visible == null || visible.isBoolean)
            val width = property.nullableNonnegativeInt("width")
            val wrap = property.nullableBoolean("wrap")
            if (visible?.asBoolean() == true) NotionViewColumn(propertyId, name, width, wrap) else null
        }
    }

    private fun parseConfiguration(
        configuration: JsonNode?,
        type: String,
    ): NotionViewConfiguration =
        when (type) {
            "table" -> parseTable(configuration)
            "list" -> NotionViewConfiguration.ListView
            "gallery" -> parseGallery(configuration)
            else -> throw IllegalArgumentException("Unsupported view type")
        }

    private fun parseTable(configuration: JsonNode?): NotionViewConfiguration.Table =
        NotionViewConfiguration.Table(
            wrapCells = configuration?.nullableBoolean("wrap_cells") ?: true,
            frozenColumns = configuration?.nullableNonnegativeInt("frozen_column_index") ?: 0,
            showVerticalLines = configuration?.nullableBoolean("show_vertical_lines") ?: true,
        )

    private fun parseGallery(configuration: JsonNode?): NotionViewConfiguration.Gallery =
        NotionViewConfiguration.Gallery(
            cover = configuration?.nullableObject("cover")?.let(::parseGalleryCover),
            size = parseGallerySize(configuration?.nullableText("cover_size")),
            aspect = parseGalleryAspect(configuration?.nullableText("cover_aspect")),
            layout = parseGalleryLayout(configuration?.nullableText("card_layout")),
        )

    private fun parseGallerySize(size: String?): NotionGallerySize =
        when (size) {
            null, "medium" -> NotionGallerySize.MEDIUM
            "small" -> NotionGallerySize.SMALL
            "large" -> NotionGallerySize.LARGE
            else -> throw IllegalArgumentException("Invalid gallery size")
        }

    private fun parseGalleryAspect(aspect: String?): NotionGalleryAspect =
        when (aspect) {
            null, "cover" -> NotionGalleryAspect.COVER
            "contain" -> NotionGalleryAspect.CONTAIN
            else -> throw IllegalArgumentException("Invalid gallery aspect")
        }

    private fun parseGalleryLayout(layout: String?): NotionGalleryLayout =
        when (layout) {
            null, "list" -> NotionGalleryLayout.LIST
            "compact" -> NotionGalleryLayout.COMPACT
            else -> throw IllegalArgumentException("Invalid gallery layout")
        }

    private fun parseGalleryCover(cover: JsonNode): NotionGalleryCover =
        when (cover.requiredText("type")) {
            "page_cover" -> NotionGalleryCover.PageCover
            "page_content" -> NotionGalleryCover.PageContent
            "property" -> NotionGalleryCover.Property(cover.requiredText("property_id"))
            else -> throw IllegalArgumentException("Invalid gallery cover")
        }

    private companion object {
        val supportedViewTypes = setOf("table", "list", "gallery")
    }
}
