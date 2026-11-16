package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.MigratePostCommand
import xyz.robinjoon.notionblog.blog.application.model.MigratePostResult
import xyz.robinjoon.notionblog.blog.application.model.MigrationDatabase
import xyz.robinjoon.notionblog.blog.application.model.MigrationPlan
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind

interface PostMigrationUseCase {
    fun inspect(databaseUrl: String): MigrationDatabase

    fun prepare(selection: MigrationSelection): MigrationPlan

    fun migrate(command: MigratePostCommand): MigratePostResult
}

class MigrationSourceException(
    val kind: SyncFailureKind,
    cause: RuntimeException,
) : RuntimeException("migration source could not be read", cause)
