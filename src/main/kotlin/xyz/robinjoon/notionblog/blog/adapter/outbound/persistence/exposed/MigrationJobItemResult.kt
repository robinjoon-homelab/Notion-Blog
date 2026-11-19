package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.MigrationJobItemTable
import xyz.robinjoon.notionblog.blog.application.model.MigratePostResult
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind

internal object MigrationJobItemResult {
    const val FAILED = "FAILED"
    const val IMPORTED = "IMPORTED"
    const val PARTIAL = "PARTIAL"
    const val SKIPPED = "SKIPPED"
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    fun read(row: ResultRow): MigratePostResult? =
        when (row[MigrationJobItemTable.resultKind]) {
            null -> {
                null
            }

            IMPORTED, PARTIAL -> {
                MigratePostResult.Imported(postId(row), diagnostics(row))
            }

            SKIPPED -> {
                MigratePostResult.Skipped(postId(row))
            }

            FAILED -> {
                MigratePostResult.Failed(
                    SyncFailureKind.valueOf(requireNotNull(row[MigrationJobItemTable.resultFailureKind])),
                    diagnostics(row).singleOrNull(),
                )
            }

            else -> {
                error("unknown stored migration result")
            }
        }

    fun write(
        update: UpdateBuilder<*>,
        result: MigratePostResult,
    ) {
        when (result) {
            is MigratePostResult.Imported -> {
                update[MigrationJobItemTable.resultKind] = if (result.warnings.isEmpty()) IMPORTED else PARTIAL
                update[MigrationJobItemTable.resultPostId] = result.postId.value
                update[MigrationJobItemTable.resultDiagnostics] = encode(result.warnings)
            }

            is MigratePostResult.Skipped -> {
                update[MigrationJobItemTable.resultKind] = SKIPPED
                update[MigrationJobItemTable.resultPostId] = result.postId.value
            }

            is MigratePostResult.Failed -> {
                update[MigrationJobItemTable.resultKind] = FAILED
                update[MigrationJobItemTable.resultFailureKind] = result.kind.name
                update[MigrationJobItemTable.resultDiagnostics] = encode(listOfNotNull(result.diagnostic))
            }
        }
    }

    fun clear(update: UpdateBuilder<*>) {
        update[MigrationJobItemTable.resultKind] = null
        update[MigrationJobItemTable.resultPostId] = null
        update[MigrationJobItemTable.resultFailureKind] = null
        update[MigrationJobItemTable.resultDiagnostics] = null
    }

    private fun postId(row: ResultRow): PostId = PostId(requireNotNull(row[MigrationJobItemTable.resultPostId]))

    private fun diagnostics(row: ResultRow): List<MigrationFailureDetail> =
        row[MigrationJobItemTable.resultDiagnostics]
            ?.let { mapper.readValue(it, Array<MigrationFailureDetail>::class.java).toList() }
            .orEmpty()

    private fun encode(diagnostics: List<MigrationFailureDetail>): String? =
        diagnostics.takeIf(List<MigrationFailureDetail>::isNotEmpty)?.let(mapper::writeValueAsString)
}
