package xyz.robinjoon.notionblog.blog.application.port.output.source

import xyz.robinjoon.notionblog.blog.application.model.MigrationCatalogPage
import xyz.robinjoon.notionblog.blog.application.model.MigrationDatabase
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection

interface PostMigrationSource {
    fun inspect(databaseUrl: String): MigrationDatabase

    fun readCatalog(
        selection: MigrationSelection,
        cursor: String? = null,
    ): MigrationCatalogPage
}
