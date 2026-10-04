package xyz.robinjoon.notionblog.adapter.output.notion.client

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.adapter.output.notion.NotionFailureTranslator
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDataSourceResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDatabaseProperty
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDatabaseResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDatabaseViewResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGalleryAspect
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGalleryCover
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGalleryLayout
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGallerySize
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionPageParentResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionPaginationResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionParentResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionSettingsRowResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionViewColumn
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionViewConfiguration
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionViewQueryResponse
import xyz.robinjoon.notionblog.adapter.output.notion.mapping.NotionIdNormalizer
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.Locale

internal class NotionApiClient(
    baseUrl: String,
    token: String,
    requestTimeout: Duration,
    collectionTimeout: Duration,
) {
    private val restClient: RestClient
    private val collectionTimeoutNanos: Long
    private val failureTranslator = NotionFailureTranslator()

    init {
        val validatedBaseUrl = validateBaseUrl(baseUrl)
        require(token.isNotBlank()) { "Notion token must not be blank" }
        require(requestTimeout.isPositive) { "Notion request timeout must be positive" }
        require(collectionTimeout.isPositive) { "Notion collection timeout must be positive" }
        collectionTimeoutNanos = collectionTimeout.toNanos()

        val httpClient = HttpClient.newBuilder()
            .connectTimeout(requestTimeout)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(requestTimeout)
        }
        restClient = RestClient.builder()
            .baseUrl(validatedBaseUrl)
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .defaultHeader(NOTION_VERSION_HEADER, SUPPORTED_API_VERSION)
            .build()
    }

    fun fetchPage(pageId: String, propertyIds: List<String> = emptyList()): NotionPageResponse {
        require(pageId.isNotBlank()) { "Notion page ID must not be blank" }
        require(propertyIds.all(String::isNotBlank)) { "Notion property IDs must not be blank" }
        val response = execute {
            restClient.get()
                .uri { builder ->
                    builder.path("/pages/{pageId}")
                        .apply { propertyIds.forEach { queryParam("filter_properties[]", it) } }
                        .build(pageId)
                }
                .retrieve()
                .body(JsonNode::class.java)
        }
        return parsePage(response)
    }

    fun fetchDatabase(databaseId: String): NotionDatabaseResponse {
        require(databaseId.isNotBlank()) { "Notion database ID must not be blank" }
        val response = execute {
            restClient.get()
                .uri("/databases/{databaseId}", databaseId)
                .retrieve()
                .body(JsonNode::class.java)
        }
        return parseResponse {
            require(response.requiredText("object") == "database")
            NotionDatabaseResponse(
                id = response.requiredText("id"),
                title = response.requiredArray("title").joinToString("") { title ->
                    title.nullableText("plain_text") ?: throw IllegalArgumentException("Missing database title text")
                },
                url = response.nullableText("public_url") ?: response.nullableText("url"),
                inTrash = response.requiredBoolean("in_trash"),
                parent = parseParent(response.requiredObject("parent")),
                dataSourceIds = response.requiredArray("data_sources").toList().map { it.requiredText("id") },
            )
        }
    }

    fun fetchDatabaseViews(databaseId: String, cursor: String? = null): NotionPaginationResponse<String> {
        require(databaseId.isNotBlank()) { "Notion database ID must not be blank" }
        val response = execute {
            restClient.get()
                .uri { builder ->
                    builder.path("/views")
                        .queryParam("database_id", databaseId)
                        .queryParam(PAGE_SIZE_PARAMETER, PAGE_SIZE)
                        .apply { cursor?.let { queryParam(START_CURSOR_PARAMETER, it) } }
                        .build()
                }
                .retrieve()
                .body(JsonNode::class.java)
        }
        return parseReferencePage(response, "view")
    }

    fun fetchDatabaseView(viewId: String): NotionDatabaseViewResponse {
        require(viewId.isNotBlank()) { "Notion view ID must not be blank" }
        val response = execute {
            restClient.get()
                .uri("/views/{viewId}", viewId)
                .retrieve()
                .body(JsonNode::class.java)
        }
        return parseResponse {
            require(response.requiredText("object") == "view")
            val parent = response.requiredObject("parent")
            require(parent.requiredText("type") == "database_id")
            val type = response.requiredText("type")
            val configuration = if (type in SUPPORTED_VIEW_TYPES) response.nullableObject("configuration") else null
            require(configuration == null || configuration.requiredText("type") == type)
            NotionDatabaseViewResponse(
                id = response.requiredText("id"),
                databaseId = parent.requiredText("database_id"),
                name = response.nullableText("name")?.takeIf(String::isNotBlank) ?: "데이터베이스 보기",
                type = type,
                dataSourceId = response.nullableText("data_source_id"),
                columns = configuration?.let(::parseViewColumns),
                configuration = if (type in SUPPORTED_VIEW_TYPES) parseViewConfiguration(configuration, type) else null,
            )
        }
    }

    fun fetchDataSource(dataSourceId: String): NotionDataSourceResponse {
        require(dataSourceId.isNotBlank()) { "Notion data source ID must not be blank" }
        val response = execute {
            restClient.get()
                .uri("/data_sources/{dataSourceId}", dataSourceId)
                .retrieve()
                .body(JsonNode::class.java)
        }
        return parseResponse {
            require(response.requiredText("object") == "data_source")
            NotionDataSourceResponse(
                id = response.requiredText("id"),
                properties = response.requiredObject("properties").properties().map { (_, property) ->
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
    }

    fun queryDataSourcePage(dataSourceId: String, cursor: String? = null): NotionPaginationResponse<NotionPageResponse> {
        require(dataSourceId.isNotBlank()) { "Notion data source ID must not be blank" }
        require(cursor == null || cursor.isNotBlank()) { "Notion data source cursor must not be blank" }
        val requestBody = buildMap<String, Any> {
            put("result_type", "page")
            put(PAGE_SIZE_PARAMETER, PAGE_SIZE)
            cursor?.let { put(START_CURSOR_PARAMETER, it) }
        }
        val response = execute {
            restClient.post()
                .uri("/data_sources/{dataSourceId}/query", dataSourceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(JsonNode::class.java)
        }
        return parseResponse {
            require(response.requiredText("object") == "list")
            parsePagination(response) { page ->
                require(page.requiredText("object") == "page")
                parsePage(page)
            }
        }
    }

    fun fetchBlockParent(blockId: String): NotionParentResponse {
        val requestedId = NotionIdNormalizer.normalize(blockId)
        val response = execute {
            restClient.get()
                .uri("/blocks/{blockId}", blockId)
                .retrieve()
                .body(JsonNode::class.java)
        }
        return parseResponse {
            require(response.requiredText("object") == "block")
            require(NotionIdNormalizer.normalize(response.requiredText("id")) == requestedId)
            parseParent(response.requiredObject("parent"))
        }
    }

    fun createViewQuery(viewId: String): NotionViewQueryResponse {
        require(viewId.isNotBlank()) { "Notion view ID must not be blank" }
        val response = execute {
            restClient.post()
                .uri("/views/{viewId}/queries", viewId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf(PAGE_SIZE_PARAMETER to PAGE_SIZE))
                .retrieve()
                .body(JsonNode::class.java)
        }
        return parseResponse {
            require(response.requiredText("object") == "view_query")
            NotionViewQueryResponse(
                queryId = response.requiredText("id"),
                viewId = response.requiredText("view_id"),
                page = parsePagination(response) { parseReference(it, "page") },
            )
        }
    }

    fun fetchViewQueryResults(viewId: String, queryId: String, cursor: String): NotionPaginationResponse<String> {
        require(viewId.isNotBlank()) { "Notion view ID must not be blank" }
        require(queryId.isNotBlank()) { "Notion view query ID must not be blank" }
        require(cursor.isNotBlank()) { "Notion view query cursor must not be blank" }
        val response = execute {
            restClient.get()
                .uri { builder ->
                    builder.path("/views/{viewId}/queries/{queryId}")
                        .queryParam(PAGE_SIZE_PARAMETER, PAGE_SIZE)
                        .queryParam(START_CURSOR_PARAMETER, cursor)
                        .build(viewId, queryId)
                }
                .retrieve()
                .body(JsonNode::class.java)
        }
        return parseReferencePage(response, "page")
    }

    fun fetchDirectBlockChildren(blockId: String): List<NotionBlockEnvelope> {
        require(blockId.isNotBlank()) { "Notion block ID must not be blank" }
        val startedAt = System.nanoTime()
        return collectPages(startedAt) { cursor -> fetchBlockChildrenPage(blockId, cursor) }
    }

    fun fetchBlockChildrenPage(blockId: String, cursor: String? = null): NotionPaginationResponse<NotionBlockEnvelope> {
        require(blockId.isNotBlank()) { "Notion block ID must not be blank" }
        require(cursor == null || cursor.isNotBlank()) { "Notion block cursor must not be blank" }
        return execute {
            restClient.get()
                .uri { builder ->
                    builder.path("/blocks/{blockId}/children")
                        .queryParam(PAGE_SIZE_PARAMETER, PAGE_SIZE)
                        .apply { cursor?.let { queryParam(START_CURSOR_PARAMETER, it) } }
                        .build(blockId)
                }
                .retrieve()
                .body(JsonNode::class.java)
        }.let(::parseBlockPage)
    }

    fun fetchSettingsRows(dataSourceId: String): List<NotionSettingsRowResponse> {
        require(dataSourceId.isNotBlank()) { "Notion settings data source ID must not be blank" }
        val startedAt = System.nanoTime()
        return collectPages(startedAt) { cursor ->
            val requestBody = buildMap<String, Any> {
                put(PAGE_SIZE_PARAMETER, PAGE_SIZE)
                cursor?.let { put(START_CURSOR_PARAMETER, it) }
            }
            execute {
                restClient.post()
                    .uri("/data_sources/{dataSourceId}/query", dataSourceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode::class.java)
            }.let(::parseSettingsPage)
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

    private fun parsePage(node: JsonNode): NotionPageResponse = try {
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
    } catch (exception: IllegalArgumentException) {
        throw failureTranslator.invalidResponse()
    }

    private fun parsePageParent(node: JsonNode): NotionPageParentResponse = NotionPageParentResponse(
        type = node.requiredText("type"),
        pageId = node.optionalText("page_id"),
        dataSourceId = node.nullableText("data_source_id"),
    )

    private fun parseParent(node: JsonNode): NotionParentResponse = when (val type = node.requiredText("type")) {
        "page_id" -> NotionParentResponse.Page(node.requiredText("page_id"))

        "block_id" -> NotionParentResponse.Block(node.requiredText("block_id"))

        "database_id" -> NotionParentResponse.Database(node.requiredText("database_id"))

        "data_source_id" -> NotionParentResponse.DataSource(node.requiredText("data_source_id"))

        "workspace" -> {
            require(node.requiredBoolean("workspace"))
            NotionParentResponse.Workspace
        }

        else -> NotionParentResponse.Unsupported(type)
    }

    private fun parseViewColumns(configuration: JsonNode): List<NotionViewColumn>? {
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

    private fun parseViewConfiguration(configuration: JsonNode?, type: String): NotionViewConfiguration = when (type) {
        "table" -> NotionViewConfiguration.Table(
            wrapCells = configuration?.nullableBoolean("wrap_cells") ?: true,
            frozenColumns = configuration?.nullableNonnegativeInt("frozen_column_index") ?: 0,
            showVerticalLines = configuration?.nullableBoolean("show_vertical_lines") ?: true,
        )

        "list" -> NotionViewConfiguration.ListView

        "gallery" -> NotionViewConfiguration.Gallery(
            cover = configuration?.nullableObject("cover")?.let(::parseGalleryCover),
            size = when (configuration?.nullableText("cover_size")) {
                null, "medium" -> NotionGallerySize.MEDIUM
                "small" -> NotionGallerySize.SMALL
                "large" -> NotionGallerySize.LARGE
                else -> throw IllegalArgumentException("Invalid gallery size")
            },
            aspect = when (configuration?.nullableText("cover_aspect")) {
                null, "cover" -> NotionGalleryAspect.COVER
                "contain" -> NotionGalleryAspect.CONTAIN
                else -> throw IllegalArgumentException("Invalid gallery aspect")
            },
            layout = when (configuration?.nullableText("card_layout")) {
                null, "list" -> NotionGalleryLayout.LIST
                "compact" -> NotionGalleryLayout.COMPACT
                else -> throw IllegalArgumentException("Invalid gallery layout")
            },
        )

        else -> throw IllegalArgumentException("Unsupported view type")
    }

    private fun parseGalleryCover(cover: JsonNode): NotionGalleryCover = when (cover.requiredText("type")) {
        "page_cover" -> NotionGalleryCover.PageCover
        "page_content" -> NotionGalleryCover.PageContent
        "property" -> NotionGalleryCover.Property(cover.requiredText("property_id"))
        else -> throw IllegalArgumentException("Invalid gallery cover")
    }

    private fun parseReferencePage(node: JsonNode, type: String): NotionPaginationResponse<String> = parseResponse {
        require(node.requiredText("object") == "list" && node.requiredText("type") == type)
        node.requiredObject(type)
        parsePagination(node) { parseReference(it, type) }
    }

    private fun parseReference(node: JsonNode, type: String): String {
        require(node.requiredText("object") == type)
        return node.requiredText("id")
    }

    private fun parseBlockPage(node: JsonNode): NotionPaginationResponse<NotionBlockEnvelope> = parsePagination(node) { block ->
        NotionBlockEnvelope(
            id = block.requiredText("id"),
            type = block.requiredText("type"),
            hasChildren = block.requiredBoolean("has_children"),
            inTrash = block.requiredBoolean("in_trash"),
            payload = block.requiredObject(block.requiredText("type")),
        )
    }

    private fun parseSettingsPage(node: JsonNode): NotionPaginationResponse<NotionSettingsRowResponse> = parsePagination(node) { row ->
        NotionSettingsRowResponse(
            id = row.requiredText("id"),
            properties = row.requiredObject("properties"),
        )
    }

    private fun <T> parsePagination(
        node: JsonNode,
        parseResult: (JsonNode) -> T,
    ): NotionPaginationResponse<T> = try {
        val status = node.get("request_status")
        require(status == null || (status.isObject && status.requiredText("type") == "complete"))
        val results = node.requiredArray("results").toList().map(parseResult)
        NotionPaginationResponse(
            results = results,
            hasMore = node.requiredBoolean("has_more"),
            nextCursor = node.optionalCursor(),
        )
    } catch (exception: IllegalArgumentException) {
        throw failureTranslator.invalidResponse()
    }

    private fun <T> parseResponse(parse: () -> T): T = try {
        parse()
    } catch (exception: IllegalArgumentException) {
        throw failureTranslator.invalidResponse()
    }

    private fun execute(request: () -> JsonNode?): JsonNode = try {
        request() ?: throw failureTranslator.invalidResponse()
    } catch (exception: RestClientResponseException) {
        throw failureTranslator.httpFailure(exception.statusCode.value())
    } catch (exception: ResourceAccessException) {
        throw failureTranslator.requestFailure()
    } catch (exception: RestClientException) {
        throw failureTranslator.invalidResponse()
    }

    private fun JsonNode.requiredText(field: String): String = optionalText(field)
        ?: throw IllegalArgumentException("Missing required field")

    private fun JsonNode.optionalText(field: String): String? = get(field)
        ?.takeUnless(JsonNode::isNull)
        ?.takeIf(JsonNode::isString)
        ?.stringValue()
        ?.takeIf(String::isNotBlank)

    private fun JsonNode.requiredNullableText(field: String): String? {
        val value = get(field) ?: throw IllegalArgumentException("Missing required field")
        if (value.isNull) return null
        require(value.isString && value.stringValue().isNotBlank())
        return value.stringValue()
    }

    private fun JsonNode.nullableText(field: String): String? {
        val value = get(field)?.takeUnless(JsonNode::isNull) ?: return null
        require(value.isString)
        return value.stringValue()
    }

    private fun JsonNode.optionalCursor(): String? {
        val cursor = get("next_cursor") ?: return null
        if (cursor.isNull) {
            return null
        }
        return cursor.takeIf(JsonNode::isString)?.stringValue()
            ?: throw IllegalArgumentException("Invalid pagination cursor")
    }

    private fun JsonNode.requiredBoolean(field: String): Boolean = get(field)
        ?.takeIf(JsonNode::isBoolean)
        ?.asBoolean()
        ?: throw IllegalArgumentException("Missing required field")

    private fun JsonNode.nullableBoolean(field: String): Boolean? {
        val value = get(field)?.takeUnless(JsonNode::isNull) ?: return null
        require(value.isBoolean)
        return value.asBoolean()
    }

    private fun JsonNode.nullableNonnegativeInt(field: String): Int? {
        val value = get(field)?.takeUnless(JsonNode::isNull) ?: return null
        require(value.isInt && value.intValue() >= 0)
        return value.intValue()
    }

    private fun JsonNode.nullableObject(field: String): JsonNode? {
        val value = get(field)?.takeUnless(JsonNode::isNull) ?: return null
        require(value.isObject)
        return value
    }

    private fun JsonNode.requiredObject(field: String): JsonNode = get(field)
        ?.takeIf(JsonNode::isObject)
        ?: throw IllegalArgumentException("Missing required object")

    private fun JsonNode.requiredArray(field: String): JsonNode = get(field)
        ?.takeIf(JsonNode::isArray)
        ?: throw IllegalArgumentException("Missing required array")

    private fun validateBaseUrl(value: String): String {
        require(value.isNotBlank()) { "Notion base URL must not be blank" }
        val uri = runCatching { URI(value.trim()) }.getOrNull()
        require(uri != null && uri.isAbsolute && uri.host != null) { "Notion base URL must be an absolute HTTP URL" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Notion base URL must not contain userinfo, a query, or a fragment"
        }

        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT)
        val isOfficialOrigin = scheme == "https" && host == OFFICIAL_HOST && (uri.port == -1 || uri.port == HTTPS_PORT)
        val isLoopbackOrigin = (scheme == "http" || scheme == "https") && host in LOOPBACK_HOSTS
        require(isOfficialOrigin || isLoopbackOrigin) {
            "Notion base URL must use the official HTTPS origin or a loopback test host"
        }
        return value.trim().trimEnd('/')
    }

    private companion object {
        const val SUPPORTED_API_VERSION = "2026-03-11"
        const val NOTION_VERSION_HEADER = "Notion-Version"
        const val PAGE_SIZE = 100
        const val PAGE_SIZE_PARAMETER = "page_size"
        const val START_CURSOR_PARAMETER = "start_cursor"
        const val OFFICIAL_HOST = "api.notion.com"
        const val HTTPS_PORT = 443
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
        val SUPPORTED_VIEW_TYPES = setOf("table", "list", "gallery")
    }
}
