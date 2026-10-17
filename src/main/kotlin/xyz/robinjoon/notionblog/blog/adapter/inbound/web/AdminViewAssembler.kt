package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminFilterView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminPendingImportView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminPostRowView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminPostsView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminPreviewView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminSearchView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.BadgeView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.NoticeView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PaginationView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PublishActionsView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PublishFormView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PublishPanelView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.SourceStatusView
import xyz.robinjoon.notionblog.blog.application.model.AdminPostActions
import xyz.robinjoon.notionblog.blog.application.model.AdminPostList
import xyz.robinjoon.notionblog.blog.application.model.AdminPostPreview
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.AdminPostSummary
import xyz.robinjoon.notionblog.blog.application.model.PreviewVersion
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter

internal class AdminViewAssembler(
    clock: Clock,
    private val pages: PostPageViewAssembler,
) {
    private val timestamp = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withZone(clock.zone)

    fun list(
        list: AdminPostList,
        filter: AdminPostFilter,
        notice: NoticeView?,
    ): AdminPostsView {
        val posts = list.posts
        return AdminPostsView(
            rows = posts.items.map(::row),
            filters = filters(filter),
            search =
                AdminSearchView(
                    filter.title,
                    filter.status
                        ?.name
                        ?.lowercase()
                        .orEmpty(),
                    posts.totalItems,
                ),
            pagination =
                PaginationView(
                    posts.number,
                    posts.totalPages,
                    filter.pageHref(posts.number - 1).takeIf { posts.hasPrevious },
                    filter.pageHref(posts.number + 1).takeIf { posts.hasNext },
                ),
            notice = notice,
        )
    }

    fun preview(
        preview: AdminPostPreview,
        version: PreviewVersion,
        notice: NoticeView?,
    ): AdminPreviewView {
        val summary = preview.summary
        return AdminPreviewView(
            page = pages.assemble(preview),
            panel =
                PublishPanelView(
                    postId = summary.postId.value.toString(),
                    badges = badges(summary.status),
                    publicHref = "/posts/${summary.postId.value}".takeIf { live(summary.status) },
                    source = source(summary),
                    actions = actions(preview.actions, summary.status, preview.history.publishedRevisionId != null, version),
                ),
            notice = notice,
        )
    }

    fun pendingImport(
        summary: AdminPostSummary,
        actions: AdminPostActions,
        notice: NoticeView?,
    ): AdminPendingImportView =
        AdminPendingImportView(
            summary.postId.value.toString(),
            summary.title,
            source(summary),
            actions(actions, summary.status, false, PreviewVersion.LATEST),
            notice,
        )

    private fun actions(
        actions: AdminPostActions,
        status: AdminPostStatus,
        hasPublished: Boolean,
        version: PreviewVersion,
    ): PublishActionsView =
        PublishActionsView(
            publish =
                actions.publish?.let {
                    PublishFormView(
                        it.revisionId.value.toString(),
                        it.generation,
                        if (live(status)) "변경사항 게시" else "게시",
                    )
                },
            showingPublished = version == PreviewVersion.PUBLISHED,
            hasPublished = hasPublished,
            canUnpublish = actions.canUnpublish,
            canArchive = actions.canArchive,
            canRestore = actions.canRestore,
        )

    private fun row(summary: AdminPostSummary): AdminPostRowView =
        AdminPostRowView("/admin/posts/${summary.postId.value}", summary.title, badges(summary.status), source(summary))

    private fun source(summary: AdminPostSummary): SourceStatusView =
        SourceStatusView(
            summary.source.editUrl.toString(),
            summary.latestCapturedAt?.let(::format) ?: "가져오기 전",
            problem(summary.source.failureKind),
            "파일 ${summary.missingMedia}개를 아직 보관하지 못했습니다. 다시 가져오면 게시할 수 있습니다.".takeIf { summary.missingMedia > 0 },
        )

    private fun filters(filter: AdminPostFilter): List<AdminFilterView> =
        listOf(AdminFilterView("전체", filter.withStatus(null).pageHref(1), filter.status == null)) +
            AdminPostStatus.entries.map { status ->
                AdminFilterView(statusLabel(status), filter.withStatus(status).pageHref(1), filter.status == status)
            }

    private fun format(instant: Instant): String = timestamp.format(instant)

    private fun live(status: AdminPostStatus): Boolean = status == AdminPostStatus.PUBLISHED || status == AdminPostStatus.CHANGES_PENDING

    private fun badges(status: AdminPostStatus): List<BadgeView> =
        when (status) {
            AdminPostStatus.PUBLISHED -> listOf(BadgeView("발행 중", "live"))
            AdminPostStatus.CHANGES_PENDING -> listOf(BadgeView("발행 중", "live"), BadgeView("수정사항 있음", "change"))
            else -> listOf(BadgeView(statusLabel(status), "draft"))
        }

    private fun statusLabel(status: AdminPostStatus): String =
        when (status) {
            AdminPostStatus.DRAFT -> "초안"
            AdminPostStatus.PUBLISHED -> "발행 중"
            AdminPostStatus.CHANGES_PENDING -> "수정사항 있음"
            AdminPostStatus.UNPUBLISHED -> "발행 취소"
            AdminPostStatus.ARCHIVED -> "보관됨"
        }

    // 원문 문제는 공개 상태를 바꾸지 않으므로 문구에서도 공개본이 유지됨을 알린다.
    private fun problem(kind: SyncFailureKind?): String? =
        when (kind) {
            null -> null
            SyncFailureKind.RETRYABLE_SOURCE -> "Notion 응답이 지연되고 있습니다. 곧 다시 시도합니다."
            SyncFailureKind.AUTHENTICATION -> "Notion 인증에 실패했습니다. 공개된 글은 그대로 유지됩니다."
            SyncFailureKind.ACCESS -> "Notion 페이지에 접근할 수 없습니다. 공개된 글은 그대로 유지됩니다."
            SyncFailureKind.CONFIGURATION -> "Notion 연결 설정을 확인해야 합니다."
            SyncFailureKind.MAPPING -> "본문을 변환하지 못했습니다. 공개된 글은 그대로 유지됩니다."
            SyncFailureKind.MEDIA -> "파일을 가져오지 못했습니다. 공개된 글은 그대로 유지됩니다."
        }
}
