package xyz.robinjoon.notionblog.adapter.outbound.notion

import org.slf4j.LoggerFactory
import xyz.robinjoon.notionblog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionDataSourceResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionDatabaseProperty
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionDatabaseViewResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionGalleryAspect
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionGalleryCover
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionGalleryLayout
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionGallerySize
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionViewColumn
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionViewConfiguration
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionViewQueryResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionBlockMapper
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionDatabaseCellMapper
import xyz.robinjoon.notionblog.adapter.outbound.notion.mapping.NotionIdNormalizer
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
import xyz.robinjoon.notionblog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Reads saved display selections without adding publication members. */
internal class NotionDatabaseViewReader(
    private val client: NotionApiClient,
    blockMapper: NotionBlockMapper,
    sourceId: SourceId,
) {
    private val cellMapper = NotionDatabaseCellMapper(blockMapper, sourceId)
    private val coverReader = NotionDataViewCoverReader(client, blockMapper)

    fun read(
        databaseId: String,
        viewId: String,
        collection: NotionDatabaseCollection,
    ): BlockNode? {
        val id = "database:$databaseId:view:$viewId"
        var name = "Database view"
        val preview =
            try {
                val view = collection.request { client.fetchDatabaseView(viewId) }
                collection.requireMatchingId(view.id, viewId)
                collection.requireMatchingId(view.databaseId, databaseId)
                if (view.type !in setOf("table", "list", "gallery")) return null
                name = view.name.ifBlank { name }
                readPreview(id, name, view, collection)
            } catch (exception: SourceAccessException) {
                logger.debug("Notion database view unavailable: {}", exception.javaClass.simpleName)
                unavailable(id, collection)
            }
        return BlockNode(BlockId(id), LayoutBlockContent.TabItem(listOf(InlineContent.Text(name)), null), children = listOf(preview))
    }

    private fun readPreview(
        id: String,
        name: String,
        view: NotionDatabaseViewResponse,
        collection: NotionDatabaseCollection,
    ): BlockNode {
        val configuration = view.configuration
        if (view.dataSourceId == null || view.columns?.isEmpty() == true || configuration == null) return unavailable(id, collection)
        val sourceId = NotionIdNormalizer.normalize(view.dataSourceId)
        val schema = collection.schema(sourceId)
        collection.requireMatchingId(schema.id, sourceId)
        val columns = view.columns ?: defaultColumns(schema)
        val selection =
            ViewSelection(
                sourceId,
                columns,
                selectedColumns(columns, schema, collection),
                resolveCoverProperty(configuration, schema),
            )
        val rows = readRows(NotionIdNormalizer.normalize(view.id), selection, collection)
        val data = dataSet(name, selection, rows)
        collection.reserve()
        return BlockNode(BlockId("$id:data"), viewContent(data, view.type, selection.configuration))
    }

    private data class ViewSelection(
        val sourceId: String,
        val columns: List<NotionViewColumn>,
        val properties: List<NotionDatabaseProperty>,
        val configuration: NotionViewConfiguration,
    ) {
        val cover: NotionGalleryCover? get() = (configuration as? NotionViewConfiguration.Gallery)?.cover

        fun requestedProperties(): List<String> {
            val coverProperty = (cover as? NotionGalleryCover.Property)?.propertyId
            return (properties.map { it.id } + listOfNotNull(coverProperty)).distinct()
        }
    }

    private fun dataSet(
        name: String,
        selection: ViewSelection,
        rows: List<DataRow>,
    ): DataSet =
        DataSet(
            name,
            selection.properties.zip(selection.columns) { property, column -> DataColumn(property.name, column.widthPixels, column.wrap) },
            rows,
            selection.properties.indexOfFirst { it.type == "title" }.takeIf { it >= 0 },
        )

    private fun resolveCoverProperty(
        configuration: NotionViewConfiguration,
        schema: NotionDataSourceResponse,
    ): NotionViewConfiguration {
        if (configuration !is NotionViewConfiguration.Gallery) return configuration
        val cover = configuration.cover as? NotionGalleryCover.Property ?: return configuration
        val property = resolveProperty(cover.propertyId, schema)
        if (property.type != "files") throw SourceMappingException("Notion gallery cover must refer to a files property")
        return configuration.copy(cover = NotionGalleryCover.Property(property.id))
    }

    private fun viewContent(
        data: DataSet,
        type: String,
        configuration: NotionViewConfiguration,
    ): DataViewContent =
        when (configuration) {
            is NotionViewConfiguration.Table -> {
                if (type != "table") throw SourceMappingException("Notion table configuration does not match its view")
                DataViewContent.Table(data, tableOptions(configuration, data.columns.size))
            }

            NotionViewConfiguration.ListView -> {
                if (type != "list") throw SourceMappingException("Notion list configuration does not match its view")
                DataViewContent.ListView(data)
            }

            is NotionViewConfiguration.Gallery -> {
                if (type != "gallery") throw SourceMappingException("Notion gallery configuration does not match its view")
                DataViewContent.Gallery(data, galleryOptions(configuration))
            }
        }

    private fun tableOptions(
        configuration: NotionViewConfiguration.Table,
        columnCount: Int,
    ): DataTableOptions =
        DataTableOptions(configuration.wrapCells, configuration.frozenColumns.coerceAtMost(columnCount), configuration.showVerticalLines)

    private fun galleryOptions(configuration: NotionViewConfiguration.Gallery): DataGalleryOptions =
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
        )

    private fun defaultColumns(schema: NotionDataSourceResponse): List<NotionViewColumn> {
        val title =
            schema.properties.singleOrNull { it.type == "title" }
                ?: throw SourceMappingException("Notion data source must have exactly one title property")
        return listOf(NotionViewColumn(title.id, null))
    }

    private fun selectedColumns(
        columns: List<NotionViewColumn>,
        schema: NotionDataSourceResponse,
        collection: NotionDatabaseCollection,
    ): List<NotionDatabaseProperty> {
        val properties = schema.properties.associateBy { it.id }
        if (properties.size != schema.properties.size) throw SourceMappingException("Notion database property IDs must be unique")
        val seen = mutableSetOf<String>()
        return columns.map { column ->
            collection.reserve()
            val property = resolveProperty(column.propertyId, schema)
            if (!seen.add(property.id)) throw SourceMappingException("Notion view property IDs must be unique")
            property.copy(name = column.name?.takeIf(String::isNotBlank) ?: property.name.ifBlank { "Untitled" })
        }
    }

    private fun resolveProperty(
        viewPropertyId: String,
        schema: NotionDataSourceResponse,
    ): NotionDatabaseProperty =
        schema.properties.singleOrNull { property ->
            property.id == viewPropertyId || decodedPropertyId(property.id) == viewPropertyId
        } ?: throw SourceMappingException("Notion view refers to an unknown or ambiguous property")

    private fun decodedPropertyId(wireId: String): String? =
        try {
            // Views can expose decoded IDs; requests and row values retain the schema's original ID.
            URLDecoder.decode(wireId.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (exception: IllegalArgumentException) {
            logger.debug("Notion database property alias unavailable: {}", exception.javaClass.simpleName)
            null
        }

    private fun readRows(
        viewId: String,
        selection: ViewSelection,
        collection: NotionDatabaseCollection,
    ): List<DataRow> {
        val query = collection.request { client.createViewQuery(viewId) }
        collection.requireMatchingId(query.viewId, viewId)
        val queryId = NotionIdNormalizer.normalize(query.queryId)
        val rowIds = collection.collectIds { cursor -> queryPage(query, queryId, cursor) }
        return rowIds.mapNotNull { rowId -> readRow(rowId, selection, collection) }
    }

    private fun queryPage(
        query: NotionViewQueryResponse,
        queryId: String,
        cursor: String?,
    ) = if (cursor == null) {
        query.page
    } else {
        try {
            client.fetchViewQueryResults(NotionIdNormalizer.normalize(query.viewId), queryId, cursor)
        } catch (exception: SourceAccessException) {
            throw RetryableSourceException("Notion view query could not be completed", exception)
        }
    }

    private fun readRow(
        rowId: String,
        selection: ViewSelection,
        collection: NotionDatabaseCollection,
    ): DataRow? {
        val requestedProperties = selection.requestedProperties()
        val page = accessiblePage(rowId, requestedProperties, collection) ?: return null
        collection.requireMatchingId(page.id, rowId)
        if (page.inTrash || page.publicUrl == null) return null
        val sourceId = page.parent.dataSourceId
        if (page.parent.type != "data_source_id" || sourceId == null) {
            throw SourceMappingException("Notion view row must belong to its data source")
        }
        collection.requireMatchingId(sourceId, selection.sourceId)
        requestedProperties.forEach { collection.reserve() }
        return cellMapper.mapRow(page, selection.properties).copy(
            icon = if (selection.configuration is NotionViewConfiguration.Table) null else coverReader.icon(page),
            cover = coverReader.read(page, selection.cover, collection::checkDeadline, collection::reserve),
        )
    }

    private fun accessiblePage(
        rowId: String,
        requestedProperties: List<String>,
        collection: NotionDatabaseCollection,
    ): NotionPageResponse? =
        try {
            collection.request { client.fetchPage(rowId, requestedProperties) }
        } catch (exception: SourceAccessException) {
            logger.debug("Notion database row unavailable: {}", exception.javaClass.simpleName)
            null
        }

    private fun unavailable(
        id: String,
        collection: NotionDatabaseCollection,
    ): BlockNode {
        collection.reserve()
        return BlockNode(BlockId("$id:unavailable"), UnsupportedBlockContent("database_view"))
    }

    private companion object {
        val logger = LoggerFactory.getLogger(NotionDatabaseViewReader::class.java)
    }
}
