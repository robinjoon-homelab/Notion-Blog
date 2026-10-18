package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
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

// statuses가 비면 보관되지 않은 글을 모두 나열한다. title은 대소문자를 구분하지 않고 제목의 어느 위치든 일치하면 된다.
data class AdminPostListQuery(
    val statuses: Set<AdminPostStatus>,
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
    val chrome: SitePresentationContext,
    val revision: PostRevision,
    val references: ContentReferences,
    val actions: AdminPostActions,
    val history: AdminPostHistory,
)

// position은 1부터 세며 비공개·보관 글을 포함한 전체 소속 기준이다.
data class SeriesMembership(
    val id: SeriesId,
    val title: String,
    val position: Int,
    val size: Int,
)

// 발행 확인 화면이 보여 주는 현재 분류다. categories는 태그의 표시 순서·이름·ID 순이다.
data class CurrentClassification(
    val categories: List<ListedCategory>,
    val series: SeriesMembership?,
) {
    companion object {
        val none = CurrentClassification(emptyList(), null)
    }
}

sealed interface AdminPostLookupResult {
    // 현재 분류는 글의 상태이며 미리보기로 고른 리비전과 무관하므로 미리보기 바깥에 둔다.
    data class Found(
        val preview: AdminPostPreview,
        val classification: CurrentClassification = CurrentClassification.none,
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
