package xyz.robinjoon.notionblog.adapter.output.notion

import xyz.robinjoon.notionblog.adapter.output.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDataSourceResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionDatabaseProperty
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGalleryAspect
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGalleryCover
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGalleryLayout
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionGallerySize
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionPaginationResponse
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionViewColumn
import xyz.robinjoon.notionblog.adapter.output.notion.dto.NotionViewConfiguration
import xyz.robinjoon.notionblog.adapter.output.notion.mapping.NotionBlockMapper
import xyz.robinjoon.notionblog.adapter.output.notion.mapping.NotionDatabaseCellMapper
import xyz.robinjoon.notionblog.adapter.output.notion.mapping.NotionIdNormalizer
import xyz.robinjoon.notionblog.application.port.output.source.RetryableSourceException
import xyz.robinjoon.notionblog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.content.DataCardLayout
import xyz.robinjoon.notionblog.domain.post.block.content.DataCardSize
import xyz.robinjoon.notionblog.domain.post.block.content.DataColumn
import xyz.robinjoon.notionblog.domain.post.block.content.DataCoverAspect
import xyz.robinjoon.notionblog.domain.post.block.content.DataGalleryOptions
import xyz.robinjoon.notionblog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.domain.post.block.content.DataTableOptions
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.net.URI

/** Keeps structural membership discovery separate from a saved display view. */
internal class NotionInlineDatabaseReader(
    private val client: NotionApiClient,
    blockMapper: NotionBlockMapper,
    sourceId: SourceId,
    maxDepth: Int,
) {
    private val cellMapper = NotionDatabaseCellMapper(blockMapper, sourceId)
    private val childrenReader = NotionDatabaseChildrenReader(client, maxDepth)
    private val coverReader = NotionDataViewCoverReader(client, blockMapper)

    fun read(
        block: BlockNode,
        sourceDocument: SourceDocumentRef,
        checkDeadline: () -> Unit,
        reserve: () -> Unit,
    ): Result {
        val reference = block.content as ReferenceBlockContent.DatabaseLink
        val databaseId = NotionIdNormalizer.normalize(reference.reference.externalId)
        var result = block.copy(
            content = reference.copy(
                reference = reference.reference.copy(externalId = databaseId),
                originalUrl = URI("https://www.notion.so/$databaseId"),
            ),
            children = emptyList(),
        )
        val database = request(checkDeadline) { client.fetchDatabase(databaseId) }
        requireMatchingId(database.id, databaseId)
        if (database.inTrash) return Result(result, emptyList())
        val schemas = mutableMapOf<String, NotionDataSourceResponse>()
        // Ownership failures must not become display fallbacks that shrink publication membership.
        val children = childrenReader.read(database, sourceDocument, schemas, checkDeadline, reserve)
        val fallback = result.content as ReferenceBlockContent.DatabaseLink
        result = result.copy(
            content = fallback.copy(
                title = database.title.ifBlank { fallback.title ?: "Database" },
                originalUrl = safeUrl(database.url) ?: fallback.originalUrl,
            ),
        )
        try {
            val viewIds = collectIds(checkDeadline, reserve) { cursor -> client.fetchDatabaseViews(databaseId, cursor) }
            if (viewIds.isEmpty()) return Result(null, children)
            val tabs = viewIds.mapNotNull { viewId -> readView(databaseId, viewId, schemas, checkDeadline, reserve) }
            if (tabs.isEmpty()) return Result(null, children)
            reserve()
            val displayed = result.copy(children = listOf(BlockNode(BlockId("database:$databaseId:views"), LayoutBlockContent.TabContainer, children = tabs)))
            return Result(displayed, children)
        } catch (_: SourceAccessException) {
            return Result(result, children)
        }
    }

    data class Result(
        val block: BlockNode?,
        val containedChildren: List<SourceDocumentRef>,
    )

    private fun readView(
        databaseId: String,
        viewId: String,
        schemas: MutableMap<String, NotionDataSourceResponse>,
        checkDeadline: () -> Unit,
        reserve: () -> Unit,
    ): BlockNode? {
        val id = "database:$databaseId:view:$viewId"
        var name = "Database view"
        val preview = try {
            val view = request(checkDeadline) { client.fetchDatabaseView(viewId) }
            requireMatchingId(view.id, viewId)
            requireMatchingId(view.databaseId, databaseId)
            if (view.type !in setOf("table", "list", "gallery")) return null
            name = view.name.ifBlank { name }
            val configuration = view.configuration
            if (view.dataSourceId == null || view.columns?.isEmpty() == true || configuration == null) {
                unavailable(id, reserve)
            } else {
                val sourceId = NotionIdNormalizer.normalize(view.dataSourceId)
                val schema = schemas.getOrPut(sourceId) { request(checkDeadline) { client.fetchDataSource(sourceId) } }
                requireMatchingId(schema.id, sourceId)
                val columns = view.columns ?: defaultColumns(schema)
                val selected = selectedColumns(columns, schema, reserve)
                validateCoverProperty(configuration, schema)
                val rows = readRows(viewId, sourceId, selected, configuration, checkDeadline, reserve)
                val data = DataSet(
                    name,
                    selected.zip(columns) { property, column -> DataColumn(property.name, column.widthPixels, column.wrap) },
                    rows,
                    selected.indexOfFirst { it.type == "title" }.takeIf { it >= 0 },
                )
                reserve()
                BlockNode(BlockId("$id:data"), viewContent(data, view.type, configuration))
            }
        } catch (_: SourceAccessException) {
            unavailable(id, reserve)
        }
        return BlockNode(BlockId(id), LayoutBlockContent.TabItem(listOf(InlineContent.Text(name)), null), children = listOf(preview))
    }

    private fun validateCoverProperty(configuration: NotionViewConfiguration, schema: NotionDataSourceResponse) {
        val cover = (configuration as? NotionViewConfiguration.Gallery)?.cover as? NotionGalleryCover.Property ?: return
        val property = schema.properties.singleOrNull { it.id == cover.propertyId }
        if (property?.type != "files") throw SourceMappingException("Notion gallery cover must refer to a files property")
    }

    private fun viewContent(data: DataSet, type: String, configuration: NotionViewConfiguration): DataViewContent = when (configuration) {
        is NotionViewConfiguration.Table -> {
            if (type != "table") throw SourceMappingException("Notion table configuration does not match its view")
            DataViewContent.Table(data, DataTableOptions(configuration.wrapCells, configuration.frozenColumns.coerceAtMost(data.columns.size), configuration.showVerticalLines))
        }

        NotionViewConfiguration.ListView -> {
            if (type != "list") throw SourceMappingException("Notion list configuration does not match its view")
            DataViewContent.ListView(data)
        }

        is NotionViewConfiguration.Gallery -> {
            if (type != "gallery") throw SourceMappingException("Notion gallery configuration does not match its view")
            DataViewContent.Gallery(
                data,
                DataGalleryOptions(
                    when (configuration.size) {
                        NotionGallerySize.SMALL -> DataCardSize.SMALL
                        NotionGallerySize.MEDIUM -> DataCardSize.MEDIUM
                        NotionGallerySize.LARGE -> DataCardSize.LARGE
                    },
                    when (configuration.aspect) {
                        NotionGalleryAspect.CONTAIN -> DataCoverAspect.CONTAIN
                        NotionGalleryAspect.COVER -> DataCoverAspect.COVER
                    },
                    when (configuration.layout) {
                        NotionGalleryLayout.LIST -> DataCardLayout.LIST
                        NotionGalleryLayout.COMPACT -> DataCardLayout.COMPACT
                    },
                ),
            )
        }
    }

    private fun defaultColumns(schema: NotionDataSourceResponse): List<NotionViewColumn> {
        val title = schema.properties.singleOrNull { it.type == "title" }
            ?: throw SourceMappingException("Notion data source must have exactly one title property")
        return listOf(NotionViewColumn(title.id, null))
    }

    private fun selectedColumns(
        columns: List<NotionViewColumn>,
        schema: NotionDataSourceResponse,
        reserve: () -> Unit,
    ): List<NotionDatabaseProperty> {
        val properties = schema.properties.associateBy { it.id }
        if (properties.size != schema.properties.size) throw SourceMappingException("Notion database property IDs must be unique")
        val seen = mutableSetOf<String>()
        return columns.map { column ->
            if (!seen.add(column.propertyId)) throw SourceMappingException("Notion view property IDs must be unique")
            reserve()
            val property = properties[column.propertyId]
                ?: throw SourceMappingException("Notion view refers to an unknown property")
            property.copy(name = column.name?.takeIf(String::isNotBlank) ?: property.name.ifBlank { "Untitled" })
        }
    }

    private fun readRows(
        viewId: String,
        sourceId: String,
        columns: List<NotionDatabaseProperty>,
        configuration: NotionViewConfiguration,
        checkDeadline: () -> Unit,
        reserve: () -> Unit,
    ): List<DataRow> {
        val query = request(checkDeadline) { client.createViewQuery(viewId) }
        requireMatchingId(query.viewId, viewId)
        val queryId = NotionIdNormalizer.normalize(query.queryId)
        val rowIds = collectIds(checkDeadline, reserve) { cursor ->
            if (cursor == null) {
                query.page
            } else {
                try {
                    client.fetchViewQueryResults(viewId, queryId, cursor)
                } catch (exception: SourceAccessException) {
                    throw RetryableSourceException("Notion view query could not be completed", exception)
                }
            }
        }
        val cover = (configuration as? NotionViewConfiguration.Gallery)?.cover
        val coverProperty = (cover as? NotionGalleryCover.Property)?.propertyId
        val requestedProperties = (columns.map { it.id } + listOfNotNull(coverProperty)).distinct()
        return rowIds.mapNotNull { rowId ->
            val page = try {
                request(checkDeadline) { client.fetchPage(rowId, requestedProperties) }
            } catch (_: SourceAccessException) {
                null
            }
            if (page == null) return@mapNotNull null
            requireMatchingId(page.id, rowId)
            if (page.inTrash || page.publicUrl == null) return@mapNotNull null
            if (page.parent.type != "data_source_id" || page.parent.dataSourceId == null) {
                throw SourceMappingException("Notion view row must belong to its data source")
            }
            requireMatchingId(page.parent.dataSourceId, sourceId)
            requestedProperties.forEach { reserve() }
            cellMapper.mapRow(page, columns).copy(
                icon = if (configuration is NotionViewConfiguration.Table) null else coverReader.icon(page),
                cover = coverReader.read(page, cover, checkDeadline, reserve),
            )
        }
    }

    private fun unavailable(id: String, reserve: () -> Unit): BlockNode {
        reserve()
        return BlockNode(BlockId("$id:unavailable"), UnsupportedBlockContent("database_view"))
    }

    private fun collectIds(
        checkDeadline: () -> Unit,
        reserve: () -> Unit,
        fetchPage: (String?) -> NotionPaginationResponse<String>,
    ): List<String> {
        val ids = linkedSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = request(checkDeadline) { fetchPage(cursor) }
            page.results.forEach { rawId ->
                val id = NotionIdNormalizer.normalize(rawId)
                if (!ids.add(id)) throw SourceMappingException("Notion view collection contains a duplicate result")
                reserve()
            }
            cursor = page.nextCursor
            if (cursor != null && !cursors.add(cursor)) throw SourceMappingException("Notion view pagination contains a cycle")
        } while (cursor != null)
        return ids.toList()
    }

    private fun <T> request(checkDeadline: () -> Unit, fetch: () -> T): T {
        checkDeadline()
        val result = fetch()
        checkDeadline()
        return result
    }

    private fun requireMatchingId(actual: String, expected: String) {
        if (NotionIdNormalizer.normalize(actual) != expected) throw SourceMappingException("Notion database response did not match the requested object")
    }

    private fun safeUrl(value: String?): URI? = value?.let { runCatching { URI(it) }.getOrNull() }
        ?.takeIf { it.scheme?.lowercase() in setOf("http", "https") && it.host != null && it.userInfo == null }
}
