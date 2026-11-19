package xyz.robinjoon.notionblog.blog.application.port.input

interface RunNextMigrationItemUseCase {
    fun runNext()

    fun renewActive()
}
