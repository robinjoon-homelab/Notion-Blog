package xyz.robinjoon.notionblog.adapter.outbound.notion.client

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionBlockEnvelope
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionDataSourceResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionDatabaseResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionDatabaseViewResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionPaginationResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionParentResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionSettingsRowResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionViewQueryResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionIdNormalizer
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
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
    private val responseParser = NotionResponseParser()

    init {
        val validatedBaseUrl = validateBaseUrl(baseUrl)
        require(token.isNotBlank()) { "Notion token must not be blank" }
        require(requestTimeout.isPositive) { "Notion request timeout must be positive" }
        require(collectionTimeout.isPositive) { "Notion collection timeout must be positive" }
        collectionTimeoutNanos = collectionTimeout.toNanos()

        val httpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(requestTimeout)
                .build()
        val requestFactory =
            JdkClientHttpRequestFactory(httpClient).apply {
                setReadTimeout(requestTimeout)
            }
        restClient =
            RestClient
                .builder()
                .baseUrl(validatedBaseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .defaultHeader(NOTION_VERSION_HEADER, SUPPORTED_API_VERSION)
                .build()
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
            execute {
                restClient
                    .get()
                    .uri { builder ->
                        builder
                            .path("/pages/{pageId}")
                            .apply { propertyIds.indices.forEach { queryParam("filter_properties[]", "{property$it}") } }
                            .build(uriVariables)
                    }.retrieve()
                    .body(JsonNode::class.java)
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
        val response = get("/databases/{databaseId}", databaseId)
        return responseParser.database(response)
    }

    fun fetchDatabaseViews(
        databaseId: String,
        cursor: String? = null,
    ): NotionPaginationResponse<String> {
        require(databaseId.isNotBlank()) { "Notion database ID must not be blank" }
        val response =
            execute {
                restClient
                    .get()
                    .uri { builder ->
                        builder
                            .path("/views")
                            .queryParam("database_id", databaseId)
                            .queryParam(PAGE_SIZE_PARAMETER, PAGE_SIZE)
                            .apply { if (cursor != null) queryParam(START_CURSOR_PARAMETER, cursor) }
                            .build()
                    }.retrieve()
                    .body(JsonNode::class.java)
            }
        return responseParser.referencePage(response, "view")
    }

    fun fetchDatabaseView(viewId: String): NotionDatabaseViewResponse {
        require(viewId.isNotBlank()) { "Notion view ID must not be blank" }
        val response = get("/views/{viewId}", viewId)
        return responseParser.databaseView(response)
    }

    fun fetchDataSource(dataSourceId: String): NotionDataSourceResponse {
        require(dataSourceId.isNotBlank()) { "Notion data source ID must not be blank" }
        val response = get("/data_sources/{dataSourceId}", dataSourceId)
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
        val response = post("/data_sources/{dataSourceId}/query", dataSourceId, requestBody)
        return responseParser.dataSourcePage(response)
    }

    fun fetchBlockParent(blockId: String): NotionParentResponse {
        val requestedId = NotionIdNormalizer.normalize(blockId)
        val response = get("/blocks/{blockId}", blockId)
        return responseParser.blockParent(response, requestedId)
    }

    fun createViewQuery(viewId: String): NotionViewQueryResponse {
        require(viewId.isNotBlank()) { "Notion view ID must not be blank" }
        val response = post("/views/{viewId}/queries", viewId, mapOf(PAGE_SIZE_PARAMETER to PAGE_SIZE))
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
            execute {
                restClient
                    .get()
                    .uri { builder ->
                        builder
                            .path("/views/{viewId}/queries/{queryId}")
                            .queryParam(PAGE_SIZE_PARAMETER, PAGE_SIZE)
                            .queryParam(START_CURSOR_PARAMETER, cursor)
                            .build(viewId, queryId)
                    }.retrieve()
                    .body(JsonNode::class.java)
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
        return execute {
            restClient
                .get()
                .uri { builder ->
                    builder
                        .path("/blocks/{blockId}/children")
                        .queryParam(PAGE_SIZE_PARAMETER, PAGE_SIZE)
                        .apply { if (cursor != null) queryParam(START_CURSOR_PARAMETER, cursor) }
                        .build(blockId)
                }.retrieve()
                .body(JsonNode::class.java)
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
            val response = post("/data_sources/{dataSourceId}/query", dataSourceId, requestBody)
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

    private fun get(
        path: String,
        id: String,
    ): JsonNode =
        execute {
            restClient
                .get()
                .uri(path, id)
                .retrieve()
                .body(JsonNode::class.java)
        }

    private fun post(
        path: String,
        id: String,
        body: Map<String, Any>,
    ): JsonNode =
        execute {
            restClient
                .post()
                .uri(path, id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode::class.java)
        }

    private fun execute(request: () -> JsonNode?): JsonNode =
        try {
            request() ?: throw failureTranslator.invalidResponse()
        } catch (exception: RestClientResponseException) {
            throw failureTranslator.httpFailure(exception.statusCode.value(), exception)
        } catch (exception: ResourceAccessException) {
            throw failureTranslator.requestFailure(exception)
        } catch (exception: RestClientException) {
            throw failureTranslator.invalidResponse(exception)
        }

    private fun validateBaseUrl(value: String): String {
        require(value.isNotBlank()) { "Notion base URL must not be blank" }
        val uri = parseBaseUri(value)
        require(uri.isAbsolute && uri.host != null) { "Notion base URL must be an absolute HTTP URL" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Notion base URL must not contain userinfo, a query, or a fragment"
        }

        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT)
        val isOfficialOrigin = scheme == "https" && host == OFFICIAL_HOST && (uri.port == -1 || uri.port == HTTPS_PORT)
        val isLoopbackOrigin = (scheme == "http" || scheme == "https") && host in loopbackHosts
        require(isOfficialOrigin || isLoopbackOrigin) {
            "Notion base URL must use the official HTTPS origin or a loopback test host"
        }
        return value.trim().trimEnd('/')
    }

    private fun parseBaseUri(value: String): URI =
        try {
            URI(value.trim())
        } catch (exception: URISyntaxException) {
            throw IllegalArgumentException("Notion base URL must be an absolute HTTP URL", exception)
        }

    private companion object {
        const val SUPPORTED_API_VERSION = "2026-03-11"
        const val NOTION_VERSION_HEADER = "Notion-Version"
        const val PAGE_SIZE = 100
        const val PAGE_SIZE_PARAMETER = "page_size"
        const val START_CURSOR_PARAMETER = "start_cursor"
        const val OFFICIAL_HOST = "api.notion.com"
        const val HTTPS_PORT = 443
        val loopbackHosts = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
    }
}
