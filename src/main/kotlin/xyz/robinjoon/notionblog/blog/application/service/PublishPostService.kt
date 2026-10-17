package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.ArchivePostResult
import xyz.robinjoon.notionblog.blog.application.model.PublishPostCommand
import xyz.robinjoon.notionblog.blog.application.model.PublishPostResult
import xyz.robinjoon.notionblog.blog.application.model.RestorePostResult
import xyz.robinjoon.notionblog.blog.application.model.UnpublishPostResult
import xyz.robinjoon.notionblog.blog.application.port.input.PostPublicationUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import java.time.Clock

// 관리자 게시 명령이다. 각 명령은 글의 게시 상태 행을 잠그므로 한 글에 대한 명령과 새 초안 리비전 저장이 하나씩 실행된다. 어느 명령도
// 원본을 호출하지 않는다.
// Exposed는 SQL 실패를 checked 예외로 알리므로 런타임 실패처럼 명시적으로 롤백한다.
@Transactional(rollbackFor = [Exception::class])
class PublishPostService(
    private val posts: PostRepository,
    private val recorder: SyncStateRecorder,
    private val clock: Clock,
) : PostPublicationUseCase {
    @Transactional(rollbackFor = [Exception::class])
    override fun publish(command: PublishPostCommand): PublishPostResult {
        val post = posts.lock(command.postId) ?: return PublishPostResult.NotFound
        val publication = post.publication
        return when {
            publication.archived -> PublishPostResult.Archived
            publication.isPublic && publication.publishedRevisionId == command.revisionId -> PublishPostResult.AlreadyPublished
            !reviewedCurrentState(post, command) -> PublishPostResult.StaleRevision
            posts.findUnboundMedia(setOf(command.revisionId)).isNotEmpty() -> PublishPostResult.MediaNotReady
            else -> PublishPostResult.Published.also { save(post.publish(command.revisionId, clock.instant())) }
        }
    }

    // 비공개 글이어도 세대를 올려, 이 명령 전에 준비된 게시 요청이 거부되게 한다.
    @Transactional(rollbackFor = [Exception::class])
    override fun unpublish(postId: PostId): UnpublishPostResult {
        val post = posts.lock(postId) ?: return UnpublishPostResult.NotFound
        save(post.unpublish())
        return if (post.publication.isPublic) UnpublishPostResult.Unpublished else UnpublishPostResult.AlreadyUnpublished
    }

    // 보관된 글은 비공개이며 더 이상 동기화하지 않는다. 리비전과 바인딩은 저장된 채로 남는다.
    @Transactional(rollbackFor = [Exception::class])
    override fun archive(postId: PostId): ArchivePostResult {
        val post = posts.lock(postId) ?: return ArchivePostResult.NotFound
        if (post.publication.archived) return ArchivePostResult.AlreadyArchived
        save(post.archive())
        recorder.cancel(SyncTarget.Post(postId))
        return ArchivePostResult.Archived
    }

    // 복원된 글은 다시 게시할 때까지 비공개로 남는다.
    @Transactional(rollbackFor = [Exception::class])
    override fun restore(postId: PostId): RestorePostResult {
        val post = posts.lock(postId) ?: return RestorePostResult.NotFound
        if (!post.publication.archived) return RestorePostResult.NotArchived
        save(post.unarchive())
        recorder.scheduleNow(SyncTarget.Post(postId), clock.instant())
        return RestorePostResult.Restored
    }

    private fun reviewedCurrentState(
        post: Post,
        command: PublishPostCommand,
    ): Boolean = post.publication.generation == command.generation && post.latestRevisionId == command.revisionId

    private fun save(post: Post) = posts.save(post, clock.instant())
}
