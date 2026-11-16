package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind

data class MigrationPlan(
    val selection: MigrationSelection,
    val entries: List<MigrationEntry>,
)

data class MigratePostCommand(
    val selection: MigrationSelection,
    val sourceDocument: SourceDocumentRef,
)

sealed interface MigratePostResult {
    data class Imported(
        val postId: PostId,
    ) : MigratePostResult

    data class Skipped(
        val postId: PostId,
    ) : MigratePostResult

    data class Failed(
        val kind: SyncFailureKind,
    ) : MigratePostResult
}
