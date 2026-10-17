package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.LegacyMigrationResult

interface MigrateLegacyPostsUseCase {
    fun migrate(): LegacyMigrationResult
}
