package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminMigrationProgressView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationDatabaseView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationFieldsView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationOptionView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationRowView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationSourceView
import xyz.robinjoon.notionblog.blog.application.model.MigrationDatabase
import xyz.robinjoon.notionblog.blog.application.model.MigrationPlan
import xyz.robinjoon.notionblog.blog.application.model.MigrationProperty

internal object MigrationViewAssembler {
    fun database(database: MigrationDatabase): MigrationDatabaseView =
        MigrationDatabaseView(
            database.title,
            MigrationNotionLinks.source(database.id),
            database.dataSources.map { source -> MigrationSourceView(source.id, source.name, fields(source.properties)) },
        )

    fun progress(plan: MigrationPlan): AdminMigrationProgressView =
        AdminMigrationProgressView(
            plan.selection,
            plan.entries.map { entry ->
                MigrationRowView(
                    entry.title,
                    entry.sourceDocument.sourceId.value,
                    entry.sourceDocument.externalId,
                    MigrationNotionLinks.source(entry.sourceDocument.externalId),
                )
            },
        )

    private fun fields(properties: List<MigrationProperty>): MigrationFieldsView =
        MigrationFieldsView(
            options(properties.filter { it.type in setOf("title", "rich_text") }, setOf("제목", "title", "이름", "name"), "title"),
            options(properties.filter { it.type == "rich_text" }, setOf("요약", "summary")),
            options(properties.filter { it.type in setOf("multi_select", "select") }, setOf("태그", "tags")),
            options(properties.filter { it.type in setOf("date", "created_time") }, setOf("작성일", "작성 시간", "작성시간", "authoredat")),
        )

    private fun options(
        properties: List<MigrationProperty>,
        names: Set<String>,
        preferredType: String? = null,
    ): List<MigrationOptionView> {
        val preferred = properties.firstOrNull { it.type == preferredType } ?: properties.firstOrNull { it.name.lowercase() in names }
        return properties.map { MigrationOptionView(it.id, "${it.name} (${it.type})", it.id == preferred?.id) }
    }
}
