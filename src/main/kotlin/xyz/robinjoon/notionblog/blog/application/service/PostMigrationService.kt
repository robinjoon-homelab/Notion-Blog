package xyz.robinjoon.notionblog.blog.application.service

import xyz.robinjoon.notionblog.blog.application.model.MigratePostCommand
import xyz.robinjoon.notionblog.blog.application.model.MigratePostResult
import xyz.robinjoon.notionblog.blog.application.model.MigrationEntry
import xyz.robinjoon.notionblog.blog.application.model.MigrationPlan
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.input.MigrationSourceException
import xyz.robinjoon.notionblog.blog.application.port.input.PostMigrationUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.OperationFailureReporter
import xyz.robinjoon.notionblog.blog.application.port.output.diagnostics.RecoveredOperation
import xyz.robinjoon.notionblog.blog.application.port.output.media.MediaFetchException
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.DuplicateSourceBindingException
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostMigrationSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.PostRevisionSource
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceException
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind

// 원본 조회와 파일 다운로드는 트랜잭션 밖에서 하고, 저장만 글별 쓰기 경계에 맡긴다.
class PostMigrationService(
    private val catalog: PostMigrationSource,
    private val revisions: PostRevisionSource,
    private val queries: MigrationPostQueryService,
    private val media: CapturePostMediaService,
    private val writer: ApplyMigratedPostService,
    private val failures: OperationFailureReporter,
) : PostMigrationUseCase {
    override fun inspect(databaseUrl: String) = readSource { catalog.inspect(databaseUrl) }

    override fun prepare(selection: MigrationSelection): MigrationPlan = readSource { buildPlan(selection) }

    private fun buildPlan(selection: MigrationSelection): MigrationPlan {
        val entries = mutableListOf<MigrationEntry>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = catalog.readCatalog(selection, cursor)
            entries += page.entries
            cursor = page.nextCursor
            if (cursor != null && !seenCursors.add(cursor)) throw SourceMappingException("migration catalog pagination did not advance")
        } while (cursor != null)
        return MigrationPlan(selection, entries.distinctBy(MigrationEntry::sourceDocument))
    }

    private fun <T> readSource(action: () -> T): T =
        try {
            action()
        } catch (exception: SourceException) {
            if (exception.causedByInterruption()) throw exception
            failures.report(RecoveredOperation.POST_MIGRATION, exception)
            throw MigrationSourceException(exception.toSyncFailureKind(), exception, MigrationFailureDiagnostics.of(exception))
        } catch (exception: IllegalArgumentException) {
            if (exception.causedByInterruption()) throw exception
            throw MigrationSourceException(SyncFailureKind.CONFIGURATION, exception, MigrationFailureDiagnostics.of(exception))
        }

    override fun migrate(command: MigratePostCommand): MigratePostResult {
        val existing = queries.existing(command.sourceDocument)
        if (existing != null) return MigratePostResult.Skipped(existing)
        return try {
            importPost(command)
        } catch (exception: SourceException) {
            failed(exception.toSyncFailureKind(), exception)
        } catch (exception: MediaFetchException) {
            failed(SyncFailureKind.MEDIA, exception)
        } catch (exception: IllegalArgumentException) {
            failed(SyncFailureKind.MAPPING, exception)
        } catch (exception: DuplicateSourceBindingException) {
            val bound = queries.existing(command.sourceDocument) ?: throw exception
            MigratePostResult.Skipped(bound)
        }
    }

    private fun importPost(command: MigratePostCommand): MigratePostResult {
        val imported = revisions.fetchRevision(command.sourceDocument, command.selection)
        require(imported.sourceDocument == command.sourceDocument) { "migration source must match the requested document" }
        val captured = media.capture(imported.sourceHostedMedia(), emptyMap())
        return writer.apply(command.selection, imported, captured)
    }

    private fun failed(
        kind: SyncFailureKind,
        cause: RuntimeException,
    ): MigratePostResult {
        if (cause.causedByInterruption()) throw cause
        failures.report(RecoveredOperation.POST_MIGRATION, cause)
        return MigratePostResult.Failed(kind, MigrationFailureDiagnostics.of(cause))
    }
}
