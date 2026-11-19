package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.MigrationJobAction
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobHistory
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobProgress
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobUpdates
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import java.util.UUID

interface MigrationJobsUseCase {
    fun start(selection: MigrationSelection): UUID

    fun history(page: Int): MigrationJobHistory

    fun progress(id: UUID): MigrationJobProgress?

    fun updates(
        id: UUID,
        after: Long,
    ): MigrationJobUpdates?

    fun change(
        id: UUID,
        action: MigrationJobAction,
    ): Boolean
}
