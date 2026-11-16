package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDataSourceResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException

internal object NotionMigrationSchema {
    fun validate(
        source: NotionDataSourceResponse,
        selection: MigrationSelection,
    ) {
        if (source.inTrash) throw SourceAccessException("Notion migration data source is in the trash")
        if (NotionIdNormalizer.normalize(source.id) != NotionIdNormalizer.normalize(selection.dataSourceId)) {
            throw SourceMappingException("Notion migration data source does not match the requested source")
        }
        val fields = selection.fields
        requireProperty(source, fields.titlePropertyId, setOf("title", "rich_text"))
        optionalProperty(source, fields.summaryPropertyId, setOf("rich_text"))
        optionalProperty(source, fields.tagsPropertyId, setOf("multi_select", "select"))
        optionalProperty(source, fields.authoredAtPropertyId, setOf("date", "created_time"))
    }

    fun requireParent(
        page: NotionPageResponse,
        selection: MigrationSelection,
    ) {
        val parentId = page.parent.dataSourceId?.let(NotionIdNormalizer::normalizeOrNull)
        if (page.parent.type != "data_source_id" || parentId != NotionIdNormalizer.normalize(selection.dataSourceId)) {
            throw SourceMappingException("Notion migration page is not a direct row of the selected data source")
        }
    }

    private fun optionalProperty(
        source: NotionDataSourceResponse,
        propertyId: String,
        types: Set<String>,
    ) {
        if (propertyId.isNotBlank()) requireProperty(source, propertyId, types)
    }

    private fun requireProperty(
        source: NotionDataSourceResponse,
        propertyId: String,
        types: Set<String>,
    ) {
        val property =
            source.properties.singleOrNull { NotionPropertyIds.same(it.id, propertyId) }
                ?: throw SourceConfigurationException("Notion migration mapping refers to a missing property")
        if (property.type !in types) throw SourceConfigurationException("Notion migration mapping has an incompatible property type")
    }
}
