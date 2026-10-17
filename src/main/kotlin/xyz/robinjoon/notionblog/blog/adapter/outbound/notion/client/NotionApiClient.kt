package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client

import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDataSourceResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDatabaseResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDatabaseViewResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPaginationResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionParentResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionSettingsRowResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionViewQueryResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping.NotionIdNormalizer
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration

internal class NotionApiClient(
    baseUrl: String,
    token: String,
    requestTimeout: Duration,
    collectionTimeout: Duration,
) {
    private val transport = NotionHttpTransport(baseUrl, token, requestTimeout)
    private val collectionTimeoutNanos: Long
    private val failureTranslator = NotionFailureTranslator()
    private val responseParser = NotionResponseParser()

    init {
        require(collectionTimeout.isPositive) { "Notion collection timeout must be positive" }
        collectionTimeoutNanos = collectionTimeout.toNanos()
    }

    fun fetchPage(
        pageId: String,
        propertyIds: List<String> = emptyList(),
    ): NotionPageResponse {
        require(pageId.isNotBlank()) { "Notion page ID must not be blank" }
        require(propertyIds.all(String::isNotBlank)) { "Notion property IDs must not be blank" }
        val uriVariables =
            buildMap {
                put("pageId", pageId)
                propertyIds.forEachIndexed { index, propertyId -> put("property$index", decodePropertyId(propertyId)) }
            }
        val response =
            transport.get { builder ->
                builder
                    .path("/pages/{pageId}")
                    .apply { propertyIds.indices.forEach { queryParam("filter_properties[]", "{property$it}") } }
                    .build(uriVariables)
            }
        return responseParser.page(response)
    }

    private fun decodePropertyId(propertyId: String): String =
        try {
            URLDecoder.decode(propertyId.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (exception: IllegalArgumentException) {
            throw failureTranslator.invalidResponse(exception)
        }

    fun fetchDatabase(databaseId: String): NotionDatabaseResponse {
        require(databaseId.isNotBlank()) { "Notion database ID must not be blank" }
        val response = transport.get("/databases/{databaseId}", databaseId)
        return responseParser.database(response)
    }

    fun fetchDatabaseViews(
        databaseId: String,
        cursor: String? = null,
    ): NotionPaginationResponse<String> {
        require(databaseId.isNotBlank()) { "Notion database ID must not be blank" }
        val response =
            transport.get { builder ->
                builder
                    .path("/views")
                    .queryParam("database_id", databaseId)
                    .queryParam(PAGE_SIZE_PARAMETER, PAGE_SIZE)
                    .apply { if (cursor != null) queryParam(START_CURSOR_PARAMETER, cursor) }
                    .build()
            }
        return responseParser.referencePage(response, "view")
    }

    fun fetchDatabaseView(viewId: String): NotionDatabaseViewResponse {
        require(viewId.isNotBlank()) { "Notion view ID must not be blank" }
        val response = transport.get("/views/{viewId}", viewId)
        return responseParser.databaseView(response)
    }

    fun fetchDataSource(dataSourceId: String): NotionDataSourceResponse {
        require(dataSourceId.isNotBlank()) { "Notion data source ID must not be blank" }
        val response = transport.get("/data_sources/{dataSourceId}", dataSourceId)
        return responseParser.dataSource(response)
    }

    fun queryDataSourcePage(
        dataSourceId: String,
        cursor: String? = null,
    ): NotionPaginationResponse<NotionPageResponse> {
        require(dataSourceId.isNotBlank()) { "Notion data source ID must not be blank" }
        require(cursor == null || cursor.isNotBlank()) { "Notion data source cursor must not be blank" }
        val requestBody =
            buildMap<String, Any> {
                put("result_type", "page")
                put(PAGE_SIZE_PARAMETER, PAGE_SIZE)
                cursor?.let { put(START_CURSOR_PARAMETER, it) }
            }
        val response = transport.post("/data_sources/{dataSourceId}/query", dataSourceId, requestBody)
        return responseParser.dataSourcePage(response)
    }

    fun queryPostRows(
        dataSourceId: String,
        propertyIds: List<String>,
        cursor: String? = null,
    ): NotionPaginationResponse<NotionPageResponse> {
        require(dataSourceId.isNotBlank()) { "Notion data source ID must not be blank" }
        require(propertyIds.all(String::isNotBlank)) { "Notion property IDs must not be blank" }
        require(cursor == null || cursor.isNotBlank()) { "Notion data source cursor must not be blank" }
        val requestBody =
            buildMap<String, Any> {
                put("result_type", "page")
                put("is_archived", false)
                put(PAGE_SIZE_PARAMETER, PAGE_SIZE)
                cursor?.let { put(START_CURSOR_PARAMETER, it) }
            }
        val response =
            transport.post(
                { builder ->
                    builder
                        .path("/data_sources/{dataSourceId}/query")
                        .apply { propertyIds.indices.forEach { queryParam("filter_properties", "{property$it}") } }
                        .build(queryVariables(dataSourceId, propertyIds))
                },
                requestBody,
            )
        return responseParser.dataSourcePage(response)
    }

    private fun queryVariables(
        dataSourceId: String,
        propertyIds: List<String>,
    ): Map<String, String> =
        buildMap {
            put("dataSourceId", dataSourceId)
            propertyIds.forEachIndexed { index, propertyId -> put("property$index", decodePropertyId(propertyId)) }
        }

    fun fetchBlockParent(blockId: String): NotionParentResponse {
        val requestedId = NotionIdNormalizer.normalize(blockId)
        val response = transport.get("/blocks/{blockId}", blockId)
        return responseParser.blockParent(response, requestedId)
    }

    fun createViewQuery(viewId: String): NotionViewQueryResponse {
        require(viewId.isNotBlank()) { "Notion view ID must not be blank" }
        val response = transport.post("/views/{viewId}/queries", viewId, mapOf(PAGE_SIZE_PARAMETER to PAGE_SIZE))
        return responseParser.viewQuery(response)
    }

    fun fetchViewQueryResults(
        viewId: String,
        queryId: String,
        cursor: String,
    ): NotionPaginationResponse<String> {
        require(viewId.isNotBlank()) { "Notion view ID must not be blank" }
        require(queryId.isNotBlank()) { "Notion view query ID must not be blank" }
        require(cursor.isNotBlank()) { "Notion view query cursor must not be blank" }
        val response =
            transport.get { builder ->
                builder
                    .path("/views/{viewId}/queries/{queryId}")
                    .queryParam(PAGE_SIZE_PARAMETER, PAGE_SIZE)
                    .queryParam(START_CURSOR_PARAMETER, cursor)
                    .build(viewId, queryId)
            }
        return responseParser.referencePage(response, "page")
    }

    fun fetchDirectBlockChildren(blockId: String): List<NotionBlockEnvelope> {
        require(blockId.isNotBlank()) { "Notion block ID must not be blank" }
        val startedAt = System.nanoTime()
        return collectPages(startedAt) { cursor -> fetchBlockChildrenPage(blockId, cursor) }
    }

    fun fetchBlockChildrenPage(
        blockId: String,
        cursor: String? = null,
    ): NotionPaginationResponse<NotionBlockEnvelope> {
        require(blockId.isNotBlank()) { "Notion block ID must not be blank" }
        require(cursor == null || cursor.isNotBlank()) { "Notion block cursor must not be blank" }
        return transport
            .get { builder ->
                builder
                    .path("/blocks/{blockId}/children")
                    .queryParam(PAGE_SIZE_PARAMETER, PAGE_SIZE)
                    .apply { if (cursor != null) queryParam(START_CURSOR_PARAMETER, cursor) }
                    .build(blockId)
            }.let(responseParser::blockPage)
    }

    fun fetchSettingsRows(dataSourceId: String): List<NotionSettingsRowResponse> {
        require(dataSourceId.isNotBlank()) { "Notion settings data source ID must not be blank" }
        val startedAt = System.nanoTime()
        return collectPages(startedAt) { cursor ->
            val requestBody =
                buildMap<String, Any> {
                    put(PAGE_SIZE_PARAMETER, PAGE_SIZE)
                    cursor?.let { put(START_CURSOR_PARAMETER, it) }
                }
            val response = transport.post("/data_sources/{dataSourceId}/query", dataSourceId, requestBody)
            responseParser.settingsPage(response)
        }
    }

    private fun <T> collectPages(
        startedAt: Long,
        fetch: (String?) -> NotionPaginationResponse<T>,
    ): List<T> {
        val results = mutableListOf<T>()
        var cursor: String? = null
        do {
            checkCollectionDeadline(startedAt)
            val page = fetch(cursor)
            checkCollectionDeadline(startedAt)
            results += page.results
            cursor = page.nextCursor
        } while (cursor != null)
        return results
    }

    private fun checkCollectionDeadline(startedAt: Long) {
        if (System.nanoTime() - startedAt >= collectionTimeoutNanos) {
            throw failureTranslator.collectionDeadlineExceeded()
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val PAGE_SIZE_PARAMETER = "page_size"
        const val START_CURSOR_PARAMETER = "start_cursor"
    }
}
