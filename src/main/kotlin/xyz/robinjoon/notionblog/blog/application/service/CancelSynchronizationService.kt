package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget

// 보관되었거나 알 수 없는 글의 일정을 제거해 동기화를 멈춘다. 글·리비전·레거시 행은 삭제하지 않으며, 보관된 글을 복원하면 다시 일정을
// 잡는다.
// Exposed는 SQL 실패를 checked 예외로 알리므로 런타임 실패처럼 명시적으로 롤백한다.
@Transactional(rollbackFor = [Exception::class])
class CancelSynchronizationService(
    private val posts: PostRepository,
    private val syncStates: SyncStateRepository,
) {
    // 게시 상태 잠금 안에서 판단하므로, 호출자가 보관 상태를 확인한 뒤 커밋된 복원은 자신이 만든 일정을 유지한다.
    @Transactional(rollbackFor = [Exception::class])
    fun cancelPost(postId: PostId) {
        val post = posts.lock(postId)
        if (post == null || post.publication.archived) {
            syncStates.delete(SyncTarget.Post(postId))
        }
    }
}
