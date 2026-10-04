package xyz.robinjoon.notionblog.adapter.output.notion

import xyz.robinjoon.notionblog.adapter.output.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDataSourceResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDatabaseResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionParentResponse
import xyz.robinjoon.notionblog.adapter.output.notion.mapping.NotionIdNormalizer
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef

/** Discovers membership from actual ownership, independently of display views and their filters. */
internal class NotionDatabaseChildrenReader(
    private val client: NotionApiClient,
    private val maxDepth: Int,
) {
    fun read(
        database: NotionDatabaseResponse,
        sourceDocument: SourceDocumentRef,
        schemas: MutableMap<String, NotionDataSourceResponse>,
        checkDeadline: () -> Unit,
        reserve: () -> Unit,
    ): List<SourceDocumentRef> {
        if (!belongsToPage(database, sourceDocument.externalId, checkDeadline, reserve)) return emptyList()
        val databaseId = NotionIdNormalizer.normalize(database.id)
        val seenSources = mutableSetOf<String>()
        val seenPages = mutableSetOf<String>()
        val children = mutableListOf<SourceDocumentRef>()
        database.dataSourceIds.forEach { rawSourceId ->
            reserve()
            val sourceId = NotionIdNormalizer.normalize(rawSourceId)
            if (!seenSources.add(sourceId)) throw SourceMappingException("Notion database contains a duplicate data source")
            val schema = request(checkDeadline) { client.fetchDataSource(sourceId) }
            if (NotionIdNormalizer.normalize(schema.id) != sourceId) {
                throw SourceMappingException("Notion data source did not match the requested object")
            }
            schemas[sourceId] = schema
            val parent = schema.parent
            validateParent(parent)
            val owned = when (parent) {
                is NotionParentResponse.Database -> NotionIdNormalizer.normalize(parent.databaseId) == databaseId
                is NotionParentResponse.DataSource -> false
                else -> throw SourceMappingException("Notion data source parent must be a database or another data source")
            }
            if (!owned || schema.inTrash) return@forEach
            val cursors = mutableSetOf<String>()
            var cursor: String? = null
            do {
                val page = request(checkDeadline) { client.queryDataSourcePage(sourceId, cursor) }
                page.results.forEach { row ->
                    reserve()
                    val rowId = NotionIdNormalizer.normalize(row.id)
                    if (!seenPages.add(rowId)) throw SourceMappingException("Notion database collection contains a duplicate page")
                    if (row.parent.type != "data_source_id" || row.parent.dataSourceId?.let(NotionIdNormalizer::normalize) != sourceId) {
                        throw SourceMappingException("Notion database page must belong to the queried data source")
                    }
                    if (!row.inTrash) children += sourceDocument.copy(externalId = rowId)
                }
                cursor = page.nextCursor
                if (cursor != null && !cursors.add(cursor)) throw SourceMappingException("Notion database pagination contains a cycle")
            } while (cursor != null)
        }
        return children
    }

    private fun belongsToPage(
        database: NotionDatabaseResponse,
        pageId: String,
        checkDeadline: () -> Unit,
        reserve: () -> Unit,
    ): Boolean {
        val seenAncestors = mutableSetOf(NotionIdNormalizer.normalize(database.id))
        var parent = database.parent
        validateDatabaseParent(parent)
        var depth = 0
        while (parent is NotionParentResponse.Block) {
            depth += 1
            if (depth > maxDepth) throw SourceMappingException("Notion database ownership exceeds the configured maximum depth")
            val blockId = NotionIdNormalizer.normalize(parent.blockId)
            if (!seenAncestors.add(blockId)) throw SourceMappingException("Notion database ownership contains a cycle")
            reserve()
            parent = request(checkDeadline) { client.fetchBlockParent(blockId) }
            validateBlockParent(parent)
        }
        return when (parent) {
            is NotionParentResponse.Page -> NotionIdNormalizer.normalize(parent.pageId) == pageId
            is NotionParentResponse.Unsupported -> throw SourceMappingException("Notion database ownership is unsupported")
            else -> false
        }
    }

    private fun validateDatabaseParent(parent: NotionParentResponse) {
        when (parent) {
            is NotionParentResponse.Page,
            is NotionParentResponse.Block,
            is NotionParentResponse.DataSource,
            NotionParentResponse.Workspace,
                -> validateParent(parent)

            else -> throw SourceMappingException("Notion database parent must be a page, block, data source or workspace")
        }
    }

    private fun validateBlockParent(parent: NotionParentResponse) {
        when (parent) {
            is NotionParentResponse.Page,
            is NotionParentResponse.Block,
            is NotionParentResponse.DataSource,
                -> validateParent(parent)

            else -> throw SourceMappingException("Notion block ownership parent is invalid or unsupported")
        }
    }

    private fun validateParent(parent: NotionParentResponse) {
        when (parent) {
            is NotionParentResponse.Page -> NotionIdNormalizer.normalize(parent.pageId)
            is NotionParentResponse.Block -> NotionIdNormalizer.normalize(parent.blockId)
            is NotionParentResponse.Database -> NotionIdNormalizer.normalize(parent.databaseId)
            is NotionParentResponse.DataSource -> NotionIdNormalizer.normalize(parent.dataSourceId)
            NotionParentResponse.Workspace -> Unit
            is NotionParentResponse.Unsupported -> throw SourceMappingException("Notion ownership parent is unsupported")
        }
    }

    private fun <T> request(checkDeadline: () -> Unit, fetch: () -> T): T {
        checkDeadline()
        val result = fetch()
        checkDeadline()
        return result
    }
}
