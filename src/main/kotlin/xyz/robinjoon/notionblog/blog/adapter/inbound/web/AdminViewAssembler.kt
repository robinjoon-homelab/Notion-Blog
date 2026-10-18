package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminFilterView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminPendingImportView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminPostRowView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminPostsView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminPreviewView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminSearchView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.BadgeView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.ClassificationView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.NoticeView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PaginationView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PublishActionsView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PublishFormView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PublishPanelView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.SeriesRefView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.SourceStatusView
import xyz.robinjoon.notionblog.blog.application.model.AdminPostActions
import xyz.robinjoon.notionblog.blog.application.model.AdminPostList
import xyz.robinjoon.notionblog.blog.application.model.AdminPostPreview
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.AdminPostSummary
import xyz.robinjoon.notionblog.blog.application.model.CurrentClassification
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
                    filter.visibility?.param.orEmpty(),
                    posts.totalItems,
                ),
            pagination =
                PaginationView(
                    posts.number,
                    posts.totalPages,
                    filter.pageHref(posts.number - 1).takeIf { posts.hasPrevious },
                    filter.pageHref(posts.number + 1).takeIf { posts.hasNext },
                ),
            allNote = ALL_NOTE,
            notice = notice,
        )
    }

    fun preview(
        preview: AdminPostPreview,
        current: CurrentClassification,
        version: PreviewVersion,
        notice: NoticeView?,
    ): AdminPreviewView {
        val summary = preview.summary
        return AdminPreviewView(
            page = pages.assemble(preview, current),
            panel =
                PublishPanelView(
                    postId = summary.postId.value.toString(),
                    badges = badges(summary),
                    publicHref = "/posts/${summary.postId.value}".takeIf { live(summary.status) },
                    source = source(summary),
                    actions = actions(preview.actions, summary.status, preview.history.publishedRevisionId != null, version),
                    classification = classification(preview, current),
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
                        if (live(status)) "변경 발행" else "발행하기",
                    )
                },
            showingPublished = version == PreviewVersion.PUBLISHED,
            hasPublished = hasPublished,
            canUnpublish = actions.canUnpublish,
            canArchive = actions.canArchive,
            canRestore = actions.canRestore,
        )

    private fun row(summary: AdminPostSummary): AdminPostRowView =
        AdminPostRowView("/admin/posts/${summary.postId.value}", summary.title, badges(summary), source(summary))

    private fun source(summary: AdminPostSummary): SourceStatusView =
        SourceStatusView(
            summary.source.editUrl.toString(),
            summary.latestCapturedAt?.let(::format) ?: "가져오기 전",
            problem(summary.source.failureKind),
            "파일 ${summary.missingMedia}개를 아직 보관하지 못했습니다. 다시 가져오면 게시할 수 있습니다.".takeIf { summary.missingMedia > 0 },
        )

    // 전체는 보관한 글을 뺀 모든 글이므로 이름과 안내 문구로 보관 글이 없음을 알린다.
    private fun filters(filter: AdminPostFilter): List<AdminFilterView> =
        listOf(AdminFilterView("전체 (보관 제외)", filter.withVisibility(null).pageHref(1), filter.visibility == null)) +
            AdminPostVisibility.entries.map { visibility ->
                AdminFilterView(visibility.label, filter.withVisibility(visibility).pageHref(1), filter.visibility == visibility)
            }

    private fun classification(
        preview: AdminPostPreview,
        current: CurrentClassification,
    ): ClassificationView =
        ClassificationView(
            categories = current.categories.map { it.name },
            series = current.series?.let { SeriesRefView(it.title, "/admin/series/${it.id.value}", it.position, it.size) },
            sourceTags = preview.revision.properties.tags,
        )

    private fun format(instant: Instant): String = timestamp.format(instant)

    private fun live(status: AdminPostStatus): Boolean = status == AdminPostStatus.PUBLISHED || status == AdminPostStatus.CHANGES_PENDING

    // 공개 상태와 작업 알림을 섞지 않는다. 가져온 변경은 공개 상태 옆의 알림 배지이고 가져오기 문제는 별도 배지다.
    private fun badges(summary: AdminPostSummary): List<BadgeView> =
        statusBadges(summary.status) + listOfNotNull(BadgeView("가져오기 문제", "warn").takeIf { summary.source.failureKind != null })

    private fun statusBadges(status: AdminPostStatus): List<BadgeView> =
        when (status) {
            AdminPostStatus.PUBLISHED -> listOf(BadgeView("공개", "live"))
            AdminPostStatus.CHANGES_PENDING -> listOf(BadgeView("공개", "live"), BadgeView("가져온 변경 있음", "change"))
            AdminPostStatus.DRAFT, AdminPostStatus.UNPUBLISHED -> listOf(BadgeView("비공개", "draft"))
            AdminPostStatus.ARCHIVED -> listOf(BadgeView("보관", "draft"))
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

    private companion object {
        const val ALL_NOTE = "전체에는 보관한 글이 들어 있지 않습니다. 보관한 글은 보관 필터에서 확인하세요."
    }
}
