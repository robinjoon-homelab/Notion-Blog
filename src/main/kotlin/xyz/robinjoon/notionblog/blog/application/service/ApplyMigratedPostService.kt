package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.CapturedMedia
import xyz.robinjoon.notionblog.blog.application.model.ImportedRevision
import xyz.robinjoon.notionblog.blog.application.model.MigratePostResult
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.MediaStoragePort
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostMigrationMappingRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import java.time.Clock
import java.util.UUID

// 글 하나의 원본 바인딩·파일·초안·작성일·매핑을 함께 저장한다. 실패한 글의 불완전한 등록은 남기지 않는다.
@Transactional(rollbackFor = [Exception::class])
class ApplyMigratedPostService(
    private val posts: PostRepository,
    private val mappings: PostMigrationMappingRepository,
    private val mediaStorage: MediaStoragePort,
    private val recorder: SyncStateRecorder,
    private val clock: Clock,
) {
    @Transactional(rollbackFor = [Exception::class])
    fun apply(
        selection: MigrationSelection,
        imported: ImportedRevision,
        media: CapturedMedia,
    ): MigratePostResult {
        val existing = posts.findBinding(imported.sourceDocument)
        if (existing != null) return MigratePostResult.Skipped(existing.postId)
        val now = clock.instant()
        val post = Post.register(PostId(UUID.randomUUID()), imported.sourceDocument, imported.authoredAt)
        val revision =
            PostRevision(PostRevisionId(UUID.randomUUID()), post.id, imported.properties, imported.content, imported.sourceRevision, now)
        posts.register(post, revision.title, now)
        mediaStorage.save(media.newRevision, now)
        posts.saveRevision(
            revision,
            media.newRevision.associate { it.sourceKey to it.mediaKey },
            allowMissingMedia = imported.warnings.any { it.stage == MigrationFailureStage.MEDIA },
        )
        mappings.save(post.id, selection)
        posts.save(post.recordRevision(revision.id), now)
        recorder.recordSuccess(SyncTarget.Post(post.id), now)
        return MigratePostResult.Imported(post.id, imported.warnings)
    }
}
