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

// allNote는 "전체"가 보관한 글을 포함하지 않음을 알리는 문구다.
data class AdminPostsView(
    val rows: List<AdminPostRowView>,
    val filters: List<AdminFilterView>,
    val search: AdminSearchView,
    val pagination: PaginationView,
    val allNote: String,
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

// position은 비공개 글을 포함한 시리즈 안 순번이다.
data class SeriesRefView(
    val title: String,
    val href: String,
    val position: Int,
    val size: Int,
)

// sourceTags는 보고 있는 내용의 Notion 원문 태그이며 읽기 전용이다. categories는 앱 태그 이름이다.
data class ClassificationView(
    val categories: List<String>,
    val series: SeriesRefView?,
    val sourceTags: List<String>,
)

data class PublishPanelView(
    val postId: String,
    val badges: List<BadgeView>,
    val publicHref: String?,
    val source: SourceStatusView,
    val actions: PublishActionsView,
    val classification: ClassificationView,
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
