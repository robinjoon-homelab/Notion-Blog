package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.CapturedMedia
import xyz.robinjoon.notionblog.blog.application.model.FetchedMedia
import xyz.robinjoon.notionblog.blog.application.model.ImportedRevision
import xyz.robinjoon.notionblog.blog.application.model.RefreshPostResult
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.MediaStoragePort
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import java.time.Clock
import java.time.Instant

// 가져온 리비전을 새 최신 초안으로 저장한다. 게시 상태는 건드리지 않으므로 관리자가 새 리비전을 게시하기 전까지 독자는 기존 게시 리비전을
// 본다.
// Exposed는 SQL 실패를 checked 예외로 알리므로 런타임 실패처럼 명시적으로 롤백한다.
@Transactional(rollbackFor = [Exception::class])
class ApplyImportedPostService(
    private val posts: PostRepository,
    private val mediaStorage: MediaStoragePort,
    private val recorder: SyncStateRecorder,
    private val clock: Clock,
    private val revisionIdFactory: () -> PostRevisionId,
) {
    // 원본 리비전이 바뀌지 않고 누락된 레거시 사본만 받았으면 imported는 null이다. 각 복구는 내려받은 대상 리비전만 채우고,
    // 새 리비전의 파일은 새 리비전에만 연결한다.
    @Transactional(rollbackFor = [Exception::class])
    fun apply(
        postId: PostId,
        imported: ImportedRevision?,
        media: CapturedMedia,
    ): RefreshPostResult {
        val post = lockSynchronizedPost(postId) ?: return RefreshPostResult.NotFound
        val now = clock.instant()
        mediaStorage.save(media.all, now)
        media.repairs.forEach { (revisionId, repaired) -> posts.bindMissingMedia(revisionId, repaired.byKey()) }
        val result = imported?.let { storeRevision(post, it, media.newRevision.byKey(), now) } ?: RefreshPostResult.Unchanged
        recorder.recordSuccess(SyncTarget.Post(postId), now)
        return result
    }

    @Transactional(rollbackFor = [Exception::class])
    fun recordFailure(
        postId: PostId,
        kind: SyncFailureKind,
    ) {
        lockSynchronizedPost(postId) ?: return
        recorder.recordFailure(SyncTarget.Post(postId), kind, clock.instant())
    }

    // 원본은 트랜잭션 밖에서 받아 오므로 그사이 글이 보관되었을 수 있다. 게시 상태 잠금 안에서 다시 판단해, 보관된 글에 새
    // 파일·초안이나 보관할 때 제거한 일정이 다시 생기지 않게 한다.
    private fun lockSynchronizedPost(postId: PostId): Post? = posts.lock(postId)?.takeUnless { it.publication.archived }

    private fun List<FetchedMedia>.byKey(): Map<SourceMediaKey, MediaKey> = associate { it.sourceKey to it.mediaKey }

    // 같은 원본 리비전을 동시에 가져온 다른 작업이 이미 저장했다.
    private fun storeRevision(
        post: Post,
        imported: ImportedRevision,
        fetched: Map<SourceMediaKey, MediaKey>,
        now: Instant,
    ): RefreshPostResult {
        val latest = post.latestRevisionId?.let { posts.findRevisionSummaries(setOf(it))[it] }
        if (latest?.sourceRevision == imported.sourceRevision) return RefreshPostResult.Unchanged

        val revision = PostRevision(revisionIdFactory(), post.id, imported.properties, imported.content, imported.sourceRevision, now)
        val sourceKeys = revision.sourceHostedMedia().mapTo(linkedSetOf()) { SourceMediaKey.of(it.url) }
        posts.saveRevision(revision, fetched.filterKeys(sourceKeys::contains))
        posts.save(post.recordRevision(revision.id), now)
        return RefreshPostResult.Updated(revision.id)
    }
}
