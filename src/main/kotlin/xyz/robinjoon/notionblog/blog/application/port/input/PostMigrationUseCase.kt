package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.ClaimedMigrationItem
import xyz.robinjoon.notionblog.blog.application.model.MigratePostCommand
import xyz.robinjoon.notionblog.blog.application.model.MigratePostResult
import xyz.robinjoon.notionblog.blog.application.model.MigrationDatabase
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail
import xyz.robinjoon.notionblog.blog.application.model.MigrationPlan
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind

interface PostMigrationUseCase {
    fun inspect(databaseUrl: String): MigrationDatabase

    fun prepare(selection: MigrationSelection): MigrationPlan

    // 선점한 글을 가져온 경우 초안과 Imported 결과를 한 번에 저장한다. 선점권을 잃었으면 null을 반환한다.
    fun migrate(
        command: MigratePostCommand,
        claim: ClaimedMigrationItem? = null,
    ): MigratePostResult?
}

class MigrationSourceException(
    val kind: SyncFailureKind,
    cause: RuntimeException,
    val diagnostic: MigrationFailureDetail? = null,
) : RuntimeException("migration source could not be read", cause)
