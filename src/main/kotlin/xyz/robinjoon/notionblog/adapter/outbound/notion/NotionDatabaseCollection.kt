package xyz.robinjoon.notionblog.adapter.outbound.notion

import xyz.robinjoon.notionblog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionDataSourceResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionPaginationResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionIdNormalizer
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException

/** Shares the enclosing post limits and fetched schemas for one inline database. */
internal class NotionDatabaseCollection(
    private val client: NotionApiClient,
    private val enforceDeadline: () -> Unit,
    private val reserveWork: () -> Unit,
) {
    private val schemas = mutableMapOf<String, NotionDataSourceResponse>()

    fun reserve() = reserveWork()

    fun checkDeadline() = enforceDeadline()

    fun <T> request(fetch: () -> T): T {
        checkDeadline()
        val result = fetch()
        checkDeadline()
        return result
    }

    fun rememberSchema(
        sourceId: String,
        schema: NotionDataSourceResponse,
    ) {
        schemas[sourceId] = schema
    }

    fun schema(sourceId: String): NotionDataSourceResponse = schemas.getOrPut(sourceId) { request { client.fetchDataSource(sourceId) } }

    fun collectIds(fetchPage: (String?) -> NotionPaginationResponse<String>): List<String> {
        val ids = linkedSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = request { fetchPage(cursor) }
            addIds(ids, page.results)
            cursor = page.nextCursor
            if (cursor != null && !cursors.add(cursor)) throw SourceMappingException("Notion view pagination contains a cycle")
        } while (cursor != null)
        return ids.toList()
    }

    private fun addIds(
        ids: MutableSet<String>,
        results: List<String>,
    ) {
        results.forEach { rawId ->
            val id = NotionIdNormalizer.normalize(rawId)
            if (!ids.add(id)) throw SourceMappingException("Notion view collection contains a duplicate result")
            reserve()
        }
    }

    fun requireMatchingId(
        actual: String,
        expected: String,
    ) {
        if (NotionIdNormalizer.normalize(actual) != expected) {
            throw SourceMappingException("Notion database response did not match the requested object")
        }
    }
}
