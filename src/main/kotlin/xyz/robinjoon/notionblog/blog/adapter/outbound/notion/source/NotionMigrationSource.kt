package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.source

import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDataSourceResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionDatabaseResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.dto.NotionPageResponse
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping.NotionBlockMappingException
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping.NotionIdNormalizer
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping.NotionMigrationLocation
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping.NotionMigrationPropertiesMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping.NotionMigrationSchema
import xyz.robinjoon.notionblog.blog.application.model.MigrationCatalogPage
import xyz.robinjoon.notionblog.blog.application.model.MigrationDataSource
import xyz.robinjoon.notionblog.blog.application.model.MigrationDatabase
import xyz.robinjoon.notionblog.blog.application.model.MigrationEntry
import xyz.robinjoon.notionblog.blog.application.model.MigrationProperty
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostMigrationSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceAccessException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId

internal class NotionMigrationSource(
    private val sourceId: SourceId,
    private val client: NotionApiClient,
) : PostMigrationSource {
    private val properties = NotionMigrationPropertiesMapper(sourceId)

    override fun inspect(databaseUrl: String): MigrationDatabase {
        val id = NotionMigrationLocation.id(databaseUrl)
        val database =
            try {
                client.fetchDatabase(id)
            } catch (exception: SourceAccessException) {
                return inspectDataSource(id, exception)
            }
        return inspectDatabase(id, database)
    }

    override fun readCatalog(
        selection: MigrationSelection,
        cursor: String?,
    ): MigrationCatalogPage {
        val id = NotionIdNormalizer.normalize(selection.dataSourceId)
        NotionMigrationSchema.validate(client.fetchDataSource(id), selection)
        val page = client.queryPostRows(id, listOf(selection.fields.titlePropertyId), cursor)
        if (page.nextCursor != null && page.nextCursor == cursor) {
            throw SourceMappingException("Notion migration pagination did not advance")
        }
        val entries =
            page.results
                .filterNot { it.inTrash }
                .map { entry(it, selection) }
                .distinctBy(MigrationEntry::sourceDocument)
        return MigrationCatalogPage(entries, page.nextCursor)
    }

    private fun inspectDatabase(
        id: String,
        database: NotionDatabaseResponse,
    ): MigrationDatabase {
        if (database.inTrash) throw SourceAccessException("Notion migration database is in the trash")
        if (NotionIdNormalizer.normalize(database.id) != id) throw SourceMappingException("Notion migration database ID does not match")
        val sources =
            database.dataSourceIds.map { sourceId ->
                val source = client.fetchDataSource(sourceId)
                requireAccessible(source, sourceId)
                source.toMigrationSource()
            }
        return MigrationDatabase(id, database.title.ifBlank { "Notion 데이터베이스" }, sources)
    }

    private fun inspectDataSource(
        id: String,
        databaseFailure: SourceAccessException,
    ): MigrationDatabase =
        try {
            val source = client.fetchDataSource(id)
            requireAccessible(source, id)
            MigrationDatabase(id, source.title.ifBlank { "Notion 데이터 소스" }, listOf(source.toMigrationSource()))
        } catch (exception: SourceException) {
            exception.addSuppressed(databaseFailure)
            throw exception
        }

    private fun entry(
        row: NotionPageResponse,
        selection: MigrationSelection,
    ): MigrationEntry =
        try {
            NotionMigrationSchema.requireParent(row, selection)
            MigrationEntry(
                SourceDocumentRef(sourceId, NotionIdNormalizer.normalize(row.id)),
                properties.title(row, selection.fields, "제목 없음"),
            )
        } catch (exception: NotionBlockMappingException) {
            throw SourceMappingException("Mapped migration catalog title is malformed", exception)
        }

    private fun requireAccessible(
        source: NotionDataSourceResponse,
        expectedId: String,
    ) {
        if (source.inTrash) throw SourceAccessException("Notion migration data source is in the trash")
        if (NotionIdNormalizer.normalize(source.id) != NotionIdNormalizer.normalize(expectedId)) {
            throw SourceMappingException("Notion migration data source ID does not match")
        }
    }

    private fun NotionDataSourceResponse.toMigrationSource(): MigrationDataSource =
        MigrationDataSource(
            NotionIdNormalizer.normalize(id),
            title.ifBlank { "데이터 소스 ${NotionIdNormalizer.normalize(id)}" },
            properties.map { MigrationProperty(it.id, it.name, it.type) },
        )
}
