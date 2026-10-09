package xyz.robinjoon.notionblog.adapter.outbound.notion.client

import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionDataSourceResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionDatabaseProperty
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionDatabaseResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionDatabaseViewResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionPageParentResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionPaginationResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionParentResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionSettingsRowResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionViewQueryResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionIdNormalizer

internal class NotionResponseParser {
    private val failureTranslator = NotionFailureTranslator()
    private val views = NotionViewResponseParser()

    fun page(node: JsonNode): NotionPageResponse =
        parseResponse {
            NotionPageResponse(
                id = node.requiredText("id"),
                parent = parsePageParent(node.requiredObject("parent")),
                url = node.requiredText("url"),
                publicUrl = node.requiredNullableText("public_url"),
                inTrash = node.requiredBoolean("in_trash"),
                lastEditedTime = node.requiredText("last_edited_time"),
                properties = node.requiredObject("properties"),
                icon = node.nullableObject("icon"),
                cover = node.nullableObject("cover"),
            )
        }

    fun database(response: JsonNode): NotionDatabaseResponse =
        parseResponse {
            require(response.requiredText("object") == "database")
            NotionDatabaseResponse(
                id = response.requiredText("id"),
                title =
                    response.requiredArray("title").joinToString("") { title ->
                        title.nullableText("plain_text") ?: throw IllegalArgumentException("Missing database title text")
                    },
                url = response.nullableText("public_url") ?: response.nullableText("url"),
                inTrash = response.requiredBoolean("in_trash"),
                parent = parseParent(response.requiredObject("parent")),
                dataSourceIds = response.requiredArray("data_sources").toList().map { it.requiredText("id") },
            )
        }

    fun databaseView(response: JsonNode): NotionDatabaseViewResponse = parseResponse { views.parse(response) }

    fun dataSource(response: JsonNode): NotionDataSourceResponse =
        parseResponse {
            require(response.requiredText("object") == "data_source")
            NotionDataSourceResponse(
                id = response.requiredText("id"),
                properties =
                    response.requiredObject("properties").properties().map { (_, property) ->
                        NotionDatabaseProperty(
                            id = property.requiredText("id"),
                            name = property.requiredText("name"),
                            type = property.requiredText("type"),
                        )
                    },
                parent = parseParent(response.requiredObject("parent")),
                inTrash = response.requiredBoolean("in_trash"),
            )
        }

    fun dataSourcePage(response: JsonNode): NotionPaginationResponse<NotionPageResponse> =
        parseResponse {
            require(response.requiredText("object") == "list")
            parsePagination(response) { result ->
                require(result.requiredText("object") == "page")
                page(result)
            }
        }

    fun blockParent(
        response: JsonNode,
        requestedId: String,
    ): NotionParentResponse =
        parseResponse {
            require(response.requiredText("object") == "block")
            require(NotionIdNormalizer.normalize(response.requiredText("id")) == requestedId)
            parseParent(response.requiredObject("parent"))
        }

    fun viewQuery(response: JsonNode): NotionViewQueryResponse =
        parseResponse {
            require(response.requiredText("object") == "view_query")
            NotionViewQueryResponse(
                queryId = response.requiredText("id"),
                viewId = response.requiredText("view_id"),
                page = parsePagination(response) { parseReference(it, "page") },
            )
        }

    fun referencePage(
        node: JsonNode,
        type: String,
    ): NotionPaginationResponse<String> =
        parseResponse {
            require(node.requiredText("object") == "list" && node.requiredText("type") == type)
            node.requiredObject(type)
            parsePagination(node) { parseReference(it, type) }
        }

    fun blockPage(node: JsonNode): NotionPaginationResponse<NotionBlockEnvelope> =
        parsePagination(node) { block ->
            NotionBlockEnvelope(
                id = block.requiredText("id"),
                type = block.requiredText("type"),
                hasChildren = block.requiredBoolean("has_children"),
                inTrash = block.requiredBoolean("in_trash"),
                payload = block.requiredObject(block.requiredText("type")),
            )
        }

    fun settingsPage(node: JsonNode): NotionPaginationResponse<NotionSettingsRowResponse> =
        parsePagination(node) { row ->
            NotionSettingsRowResponse(
                id = row.requiredText("id"),
                properties = row.requiredObject("properties"),
            )
        }

    private fun parsePageParent(node: JsonNode): NotionPageParentResponse =
        NotionPageParentResponse(
            type = node.requiredText("type"),
            pageId = node.optionalText("page_id"),
            dataSourceId = node.nullableText("data_source_id"),
        )

    private fun parseParent(node: JsonNode): NotionParentResponse =
        when (val type = node.requiredText("type")) {
            "page_id" -> {
                NotionParentResponse.Page(node.requiredText("page_id"))
            }

            "block_id" -> {
                NotionParentResponse.Block(node.requiredText("block_id"))
            }

            "database_id" -> {
                NotionParentResponse.Database(node.requiredText("database_id"))
            }

            "data_source_id" -> {
                NotionParentResponse.DataSource(node.requiredText("data_source_id"))
            }

            "workspace" -> {
                require(node.requiredBoolean("workspace"))
                NotionParentResponse.Workspace
            }

            else -> {
                NotionParentResponse.Unsupported(type)
            }
        }

    private fun parseReference(
        node: JsonNode,
        type: String,
    ): String {
        require(node.requiredText("object") == type)
        return node.requiredText("id")
    }

    private fun <T> parsePagination(
        node: JsonNode,
        parseResult: (JsonNode) -> T,
    ): NotionPaginationResponse<T> =
        parseResponse {
            val status = node.get("request_status")
            require(status == null || (status.isObject && status.requiredText("type") == "complete"))
            val results = node.requiredArray("results").toList().map(parseResult)
            NotionPaginationResponse(
                results = results,
                hasMore = node.requiredBoolean("has_more"),
                nextCursor = node.optionalCursor(),
            )
        }

    private fun <T> parseResponse(parse: () -> T): T =
        try {
            parse()
        } catch (exception: IllegalArgumentException) {
            throw failureTranslator.invalidResponse(exception)
        }
}
