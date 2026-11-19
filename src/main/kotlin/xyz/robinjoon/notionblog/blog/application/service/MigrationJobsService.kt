package xyz.robinjoon.notionblog.blog.application.service

import xyz.robinjoon.notionblog.blog.application.model.ClaimedMigrationItem
import xyz.robinjoon.notionblog.blog.application.model.MigratePostResult
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobAction
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.input.MigrationJobsUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.PostMigrationUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.RunNextMigrationItemUseCase
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class MigrationJobsService(
    private val migration: PostMigrationUseCase,
    private val state: MigrationJobStateService,
) : MigrationJobsUseCase,
    RunNextMigrationItemUseCase {
    private val active = AtomicReference<ClaimedMigrationItem?>()

    override fun start(selection: MigrationSelection): UUID = state.create(migration.prepare(selection))

    override fun history(page: Int) = state.history(page)

    override fun progress(id: UUID) = state.progress(id)

    override fun updates(
        id: UUID,
        after: Long,
    ) = state.updates(id, after)

    override fun change(
        id: UUID,
        action: MigrationJobAction,
    ) = state.change(id, action)

    override fun runNext() {
        val item = state.claim() ?: return
        active.set(item)
        try {
            val result = migration.migrate(item.command, item) ?: return
            if (result !is MigratePostResult.Imported) state.complete(item, result)
        } finally {
            active.set(null)
        }
    }

    override fun renewActive() {
        active.get()?.let(state::renew)
    }
}
