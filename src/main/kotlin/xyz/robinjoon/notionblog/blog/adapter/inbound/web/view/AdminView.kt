package xyz.robinjoon.notionblog.blog.adapter.inbound.web.view

data class NoticeView(
    val text: String,
    val error: Boolean,
)

data class BadgeView(
    val label: String,
    val tone: String,
)

data class AdminPostRowView(
    val href: String,
    val title: String,
    val badges: List<BadgeView>,
    val source: SourceStatusView,
)

data class AdminFilterView(
    val label: String,
    val href: String,
    val current: Boolean,
)

data class AdminSearchView(
    val query: String,
    val status: String,
    val total: Int,
)

data class AdminPostsView(
    val rows: List<AdminPostRowView>,
    val filters: List<AdminFilterView>,
    val search: AdminSearchView,
    val pagination: PaginationView,
    val notice: NoticeView?,
)

data class PublishFormView(
    val revisionId: String,
    val generation: Long,
    val label: String,
)

data class PublishActionsView(
    val publish: PublishFormView?,
    val showingPublished: Boolean,
    val hasPublished: Boolean,
    val canUnpublish: Boolean,
    val canArchive: Boolean,
    val canRestore: Boolean,
)

data class SourceStatusView(
    val editUrl: String,
    val capturedAt: String,
    val problem: String?,
    val mediaNotice: String?,
)

data class PublishPanelView(
    val postId: String,
    val badges: List<BadgeView>,
    val publicHref: String?,
    val source: SourceStatusView,
    val actions: PublishActionsView,
)

data class AdminPreviewView(
    val page: PostPageView,
    val panel: PublishPanelView,
    val notice: NoticeView?,
)

data class AdminPendingImportView(
    val postId: String,
    val title: String,
    val source: SourceStatusView,
    val actions: PublishActionsView,
    val notice: NoticeView?,
)
