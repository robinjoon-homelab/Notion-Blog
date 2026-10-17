package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.CatalogEntry
import xyz.robinjoon.notionblog.blog.application.model.CatalogSynchronization
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import java.time.Clock
import java.time.Instant

// 발견한 원본 문서를 비공개 관리 글로 등록한다. 등록은 게시·게시 취소를 하지 않고 기존 바인딩을 바꾸지 않는다.
// 이미 바인딩된 문서는 그 글 ID를 그대로 쓰므로 같은 문서를 여러 번 받아도 글이 하나만 생긴다.
// Exposed는 SQL 실패를 checked 예외로 알리므로 런타임 실패처럼 명시적으로 롤백한다.
@Transactional(rollbackFor = [Exception::class])
class RegisterPostService(
    private val posts: PostRepository,
    private val recorder: SyncStateRecorder,
    private val clock: Clock,
    private val postIdFactory: () -> PostId,
) {
    @Transactional(rollbackFor = [Exception::class])
    fun registerCatalog(entries: List<CatalogEntry>): CatalogSynchronization {
        val now = clock.instant()
        val registered =
            entries
                .distinctBy(CatalogEntry::sourceDocument)
                .count { entry -> ensureManaged(entry.sourceDocument, entry.title, now) }
        return CatalogSynchronization(registered)
    }

    @Transactional(rollbackFor = [Exception::class])
    fun registerSourceDocuments(sourceDocuments: Collection<SourceDocumentRef>) {
        val now = clock.instant()
        sourceDocuments.forEach { document -> ensureManaged(document, UNTITLED, now) }
    }

    // 새로 등록했을 때만 true다. 관리되지 않는 레거시 글은 바인딩된 글 ID로 등록해 기존 ID를 유지한다.
    private fun ensureManaged(
        sourceDocument: SourceDocumentRef,
        title: String,
        now: Instant,
    ): Boolean {
        val postId = posts.findBinding(sourceDocument)?.postId ?: postIdFactory()
        if (posts.find(postId) != null) return false
        posts.register(Post.register(postId, sourceDocument), title.ifBlank { UNTITLED }, now)
        recorder.scheduleNow(SyncTarget.Post(postId), now)
        return true
    }

    private companion object {
        const val UNTITLED = "Untitled"
    }
}
