package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef

data class MigrationFields(
    val titlePropertyId: String,
    val summaryPropertyId: String = "",
    val tagsPropertyId: String = "",
    val authoredAtPropertyId: String = "",
)

data class MigrationSelection(
    val dataSourceId: String,
    val fields: MigrationFields,
)

data class MigrationProperty(
    val id: String,
    val name: String,
    val type: String,
)

data class MigrationDataSource(
    val id: String,
    val name: String,
    val properties: List<MigrationProperty>,
)

data class MigrationDatabase(
    val id: String,
    val title: String,
    val dataSources: List<MigrationDataSource>,
)

data class MigrationEntry(
    val sourceDocument: SourceDocumentRef,
    val title: String,
)

data class MigrationCatalogPage(
    val entries: List<MigrationEntry>,
    val nextCursor: String?,
)
