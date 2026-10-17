package xyz.robinjoon.notionblog.blog.application.port.output.persistence

import xyz.robinjoon.notionblog.blog.application.model.PostRevisionSummary
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.post.Post
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.PostSourceBinding
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey
import java.time.Instant

// 글, 불변 리비전, 리비전별 파일 연결, 분류 연결을 다룬다. 글은 게시 상태가 생기면 관리 대상이 된다. 조회한 글에는 저장된 분류
// 연결이 함께 담긴다.
interface PostRepository {
    fun find(postId: PostId): Post?

    // 글의 게시 상태 행을 잠근 뒤 find와 같은 결과를 반환한다. 그래서 한 글에 대한 명령은 하나씩 실행된다.
    fun lock(postId: PostId): Post?

    fun findAllManaged(): List<Post>

    // 글 제목은 최신 리비전 제목을 따르며, 가져오기 전에는 등록할 때의 제목이다.
    fun findTitles(postIds: Set<PostId>): Map<PostId, String>

    // 바인딩은 관리 대상 글과 관리되지 않는 레거시 글 모두에 있다.
    fun findBinding(sourceDocument: SourceDocumentRef): PostSourceBinding?

    fun findBindingsBySourceDocuments(sourceDocuments: Set<SourceDocumentRef>): Map<SourceDocumentRef, PostSourceBinding>

    // 멱등이다. 기존 글 행과 바인딩을 유지하며, 충돌하는 바인딩이면 DuplicateSourceBindingException을 던진다.
    fun register(
        post: Post,
        title: String,
        registeredAt: Instant,
    )

    // 게시 상태를 쓰고, 저장된 최초 게시 시각이 없을 때만 그 시각을 기록한다. 분류 연결은 쓰지 않는다.
    fun save(
        post: Post,
        changedAt: Instant,
    )

    // 글의 분류 연결만 post.categoryIds로 바꾼다. 게시 상태·세대·리비전·최초 게시 시각은 쓰지 않는다.
    fun saveCategories(post: Post)

    // 새 리비전을 저장하면서, 그 리비전이 보여 주는 원본 호스팅 파일마다 저장 사본을 연결한다.
    fun saveRevision(
        revision: PostRevision,
        media: Map<SourceMediaKey, MediaKey>,
    )

    // 레거시 스냅숏 JSON을 바꾸지 않고, 파일이 아직 연결되지 않은 리비전으로 복사한다.
    fun copyLegacySnapshot(
        postId: PostId,
        revisionId: PostRevisionId,
        media: Set<MediaSource.SourceHosted>,
    )

    fun findRevision(
        postId: PostId,
        revisionId: PostRevisionId,
    ): PostRevision?

    fun findRevisionSummaries(revisionIds: Set<PostRevisionId>): Map<PostRevisionId, PostRevisionSummary>

    // 연결된 사본을 그 사본을 보여 주는 리비전별로 묶는다.
    fun findMediaBindings(revisionIds: Set<PostRevisionId>): Map<PostRevisionId, Map<SourceMediaKey, MediaKey>>

    fun findUnboundMedia(revisionIds: Set<PostRevisionId>): Map<PostRevisionId, Set<MediaSource.SourceHosted>>

    // 한 리비전에서 아직 빠진 파일 연결만 채운다. 기존 연결은 바꾸지 않는다.
    fun bindMissingMedia(
        revisionId: PostRevisionId,
        media: Map<SourceMediaKey, MediaKey>,
    )
}
