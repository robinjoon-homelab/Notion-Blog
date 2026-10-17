package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import java.net.URI
import java.time.Instant

enum class AdminPostStatus {
    DRAFT,
    PUBLISHED,
    CHANGES_PENDING,
    UNPUBLISHED,
    ARCHIVED,
}

enum class PreviewVersion {
    LATEST,
    PUBLISHED,
}

// status가 null이면 보관되지 않은 글을 모두 나열한다. title은 대소문자를 구분하지 않고 제목의 어느 위치든 일치하면 된다.
data class AdminPostListQuery(
    val status: AdminPostStatus?,
    val title: String,
    val page: PageQuery,
)

data class SourceHealth(
    val editUrl: URI,
    val lastSuccessAt: Instant?,
    val failureKind: SyncFailureKind?,
)

// missingMedia는 게시된 리비전, 없으면 최신 리비전의 파일 가운데 저장된 사본이 없는 파일 수다.
data class AdminPostSummary(
    val postId: PostId,
    val title: String,
    val status: AdminPostStatus,
    val latestCapturedAt: Instant?,
    val source: SourceHealth,
    val missingMedia: Int,
)

// publish는 게시 버튼이 활성화될 때만 있으며, 그 값은 바뀌지 않은 채 다시 전송된다.
data class AdminPostActions(
    val publish: PublishPostCommand?,
    val canUnpublish: Boolean,
    val canArchive: Boolean,
    val canRestore: Boolean,
)

// 저장된 게시 이력이다. 독자가 마지막으로 받은 리비전(있다면)과 최초 게시 시각을 담는다.
data class AdminPostHistory(
    val publishedRevisionId: PostRevisionId?,
    val firstPublishedAt: Instant?,
)

data class AdminPostPreview(
    val summary: AdminPostSummary,
    val chrome: SiteChrome,
    val revision: PostRevision,
    val references: ContentReferences,
    val actions: AdminPostActions,
    val history: AdminPostHistory,
)

sealed interface AdminPostLookupResult {
    data class Found(
        val preview: AdminPostPreview,
    ) : AdminPostLookupResult

    // 관리 대상 글이지만 아직 아무것도 가져오지 않았다.
    data class NotImported(
        val summary: AdminPostSummary,
        val actions: AdminPostActions,
    ) : AdminPostLookupResult

    data object NotFound : AdminPostLookupResult

    data object ContentUnavailable : AdminPostLookupResult
}

data class AdminPostList(
    val posts: ResultPage<AdminPostSummary>,
)
