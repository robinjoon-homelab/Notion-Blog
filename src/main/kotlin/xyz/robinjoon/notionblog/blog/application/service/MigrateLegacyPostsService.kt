package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.LegacyMigrationResult
import xyz.robinjoon.notionblog.blog.application.model.LegacyPostRecord
import xyz.robinjoon.notionblog.blog.application.model.LegacySnapshot
import xyz.robinjoon.notionblog.blog.application.port.input.MigrateLegacyPostsUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.LegacyPostStore
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import java.time.Clock
import java.time.Instant

// 전환 이전의 원본 연결 글을 모두 한 번 관리 대상으로 옮긴다. 레거시 읽기 경로가 제공하던 글만 제공하던 스냅숏의 바뀌지 않은 사본으로
// 공개하고, 나머지 글은 비공개가 된다. 레거시 행은 읽기만 하고 바꾸지 않으며 이미 관리 중인 글은 건너뛰므로, 다시 실행해도 아무 일도
// 일어나지 않는다.
// Exposed는 SQL 실패를 checked 예외로 알리므로 런타임 실패처럼 명시적으로 롤백한다.
@Transactional(rollbackFor = [Exception::class])
class MigrateLegacyPostsService(
    private val legacy: LegacyPostStore,
    private val posts: PostRepository,
    private val recorder: SyncStateRecorder,
    private val clock: Clock,
    private val revisionIdFactory: () -> PostRevisionId,
) : MigrateLegacyPostsUseCase {
    @Transactional(rollbackFor = [Exception::class])
    override fun migrate(): LegacyMigrationResult {
        val now = clock.instant()
        val records = legacy.findUnmanaged()
        val madePublic = records.count { record -> migrate(record, now) }
        return LegacyMigrationResult(migrated = records.size, madePublic = madePublic)
    }

    private fun migrate(
        record: LegacyPostRecord,
        now: Instant,
    ): Boolean {
        val postId = record.binding.postId
        val revisionId = record.snapshot?.let { snapshot -> copySnapshot(postId, snapshot) }
        val post = record.toManagedPost(revisionId)
        posts.register(post, record.title, now)
        recorder.scheduleNow(SyncTarget.Post(postId), now)
        return post.publication.isPublic
    }

    private fun copySnapshot(
        postId: PostId,
        snapshot: LegacySnapshot,
    ): PostRevisionId {
        val revisionId = revisionIdFactory()
        posts.copyLegacySnapshot(postId, revisionId, snapshot.media)
        return revisionId
    }
}
