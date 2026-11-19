package xyz.robinjoon.notionblog.blog.adapter.inbound.web.view

import java.util.UUID

data class MigrationOptionView(
    val value: String,
    val label: String,
    val selected: Boolean,
)

data class MigrationFieldsView(
    val titles: List<MigrationOptionView>,
    val summaries: List<MigrationOptionView>,
    val tags: List<MigrationOptionView>,
    val dates: List<MigrationOptionView>,
)

data class MigrationSourceView(
    val id: String,
    val name: String,
    val fields: MigrationFieldsView,
)

data class MigrationDatabaseView(
    val title: String,
    val href: String?,
    val sources: List<MigrationSourceView>,
)

data class AdminMigrationView(
    val databaseUrl: String,
    val sourceHref: String?,
    val database: MigrationDatabaseView?,
    val notice: NoticeView?,
    val history: MigrationJobHistoryView,
)

data class MigrationJobHistoryView(
    val page: Int,
    val jobs: List<MigrationJobLinkView>,
    val hasNext: Boolean,
)

data class MigrationJobLinkView(
    val id: UUID,
    val startedAt: String,
    val summary: String,
)

data class MigrationRowView(
    val position: Int,
    val title: String,
    val sourceHref: String?,
    val result: MigrationItemResultView,
)

data class AdminMigrationProgressView(
    val version: String,
    val progress: MigrationJobStatusView,
    val rows: List<MigrationRowView>,
    val notice: NoticeView?,
)

data class MigrationJobStatusView(
    val id: UUID,
    private val state: MigrationJobStateView,
    val summary: String,
    val total: Int,
    val completed: Int,
    val actions: MigrationJobActionsView,
) {
    val status = state.label
    val shouldPoll = state.shouldPoll
}

enum class MigrationJobStateView(
    val label: String,
    val shouldPoll: Boolean,
) {
    WAITING("대기 중", true),
    RUNNING("진행 중", true),
    PAUSING("현재 글을 마치면 일시 정지합니다.", true),
    PAUSED("일시 정지", false),
    COMPLETED("처리 완료", false),
}

data class MigrationJobActionsView(
    val pause: Boolean,
    val resume: Boolean,
    val retryFailed: Boolean,
)

data class MigrationJobStatusResponse(
    val version: String,
    val progress: MigrationJobStatusView,
    val results: Map<Int, MigrationItemResultView>,
)

data class MigrationItemResultView(
    val status: String,
    val message: String,
    val href: String? = null,
)
