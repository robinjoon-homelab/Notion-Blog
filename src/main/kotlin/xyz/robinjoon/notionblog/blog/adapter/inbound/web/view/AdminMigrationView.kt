package xyz.robinjoon.notionblog.blog.adapter.inbound.web.view

import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection

data class MigrationOptionView(
    val value: String,
    val label: String,
    val selected: Boolean,
)

data class MigrationFieldsView(
    val titles: List<MigrationOptionView>,
    val summaries: List<MigrationOptionView>,
    val tags: List<MigrationOptionView>,
    val dates: List<MigrationOptionView>,
)

data class MigrationSourceView(
    val id: String,
    val name: String,
    val fields: MigrationFieldsView,
)

data class MigrationDatabaseView(
    val title: String,
    val href: String?,
    val sources: List<MigrationSourceView>,
)

data class AdminMigrationView(
    val databaseUrl: String,
    val sourceHref: String?,
    val database: MigrationDatabaseView?,
    val notice: NoticeView?,
)

data class MigrationRowView(
    val title: String,
    val sourceId: String,
    val externalId: String,
    val sourceHref: String?,
)

data class AdminMigrationProgressView(
    val selection: MigrationSelection,
    val rows: List<MigrationRowView>,
)

data class MigrationImportResponse(
    val status: String,
    val message: String,
    val href: String? = null,
)
