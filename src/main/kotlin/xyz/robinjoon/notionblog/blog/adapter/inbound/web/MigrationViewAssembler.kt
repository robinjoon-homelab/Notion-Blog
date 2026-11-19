package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminMigrationProgressView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationDatabaseView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationFieldsView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationItemResultView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationJobActionsView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationJobHistoryView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationJobLinkView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationJobStateView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationJobStatusResponse
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationJobStatusView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationOptionView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationRowView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationSourceView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.NoticeView
import xyz.robinjoon.notionblog.blog.application.model.MigratePostResult
import xyz.robinjoon.notionblog.blog.application.model.MigrationDatabase
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobCounts
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobHistory
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobItem
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobProgress
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobSummary
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobUpdates
import xyz.robinjoon.notionblog.blog.application.model.MigrationProperty
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object MigrationViewAssembler {
    fun database(database: MigrationDatabase): MigrationDatabaseView =
        MigrationDatabaseView(
            database.title,
            MigrationNotionLinks.source(database.id),
            database.dataSources.map { source -> MigrationSourceView(source.id, source.name, fields(source.properties)) },
        )

    fun history(
        page: Int,
        history: MigrationJobHistory,
    ): MigrationJobHistoryView = MigrationJobHistoryView(page, history.jobs.map(::jobLink), history.hasNext)

    private fun jobLink(job: MigrationJobSummary): MigrationJobLinkView {
        val completed = job.total - job.remaining
        return MigrationJobLinkView(
            job.id,
            timestamp.format(job.createdAt),
            "${job.total}개 중 ${completed}개 처리 · ${state(job).label}",
        )
    }

    fun progress(
        job: MigrationJobProgress,
        notice: NoticeView? = null,
    ): AdminMigrationProgressView {
        val rows = job.items.map(::row)
        val totals = rows.groupingBy { it.result.status }.eachCount()
        val counts = MigrationJobCounts(totals["imported"] ?: 0, totals["partial"] ?: 0, totals["skipped"] ?: 0, totals["failed"] ?: 0)
        return AdminMigrationProgressView(job.version.toString(), status(job.summary, counts), rows, notice)
    }

    fun status(updates: MigrationJobUpdates): MigrationJobStatusResponse =
        MigrationJobStatusResponse(
            updates.version.toString(),
            status(updates.summary, updates.counts),
            updates.items.associate { it.position to result(it) },
        )

    private fun status(
        summary: MigrationJobSummary,
        counts: MigrationJobCounts,
    ): MigrationJobStatusView {
        val remaining = summary.remaining > 0
        val actions = MigrationJobActionsView(remaining && !summary.paused, remaining && summary.paused, !remaining && counts.failed > 0)
        return MigrationJobStatusView(
            summary.id,
            state(summary),
            countText(summary.total, counts),
            summary.total,
            summary.total - summary.remaining,
            actions,
        )
    }

    private fun countText(
        total: Int,
        counts: MigrationJobCounts,
    ): String =
        "전체 ${total}개 · 가져옴 ${counts.imported}개 · 일부 가져옴 ${counts.partial}개 · " +
            "기존 글 ${counts.skipped}개 · 실패 ${counts.failed}개"

    private fun state(job: MigrationJobSummary): MigrationJobStateView =
        when {
            job.remaining == 0 -> MigrationJobStateView.COMPLETED
            job.paused && job.processing -> MigrationJobStateView.PAUSING
            job.paused -> MigrationJobStateView.PAUSED
            job.processing || job.remaining < job.total -> MigrationJobStateView.RUNNING
            else -> MigrationJobStateView.WAITING
        }

    private fun row(item: MigrationJobItem): MigrationRowView =
        MigrationRowView(
            item.position,
            item.entry.title,
            MigrationNotionLinks.source(item.entry.sourceDocument.externalId),
            result(item),
        )

    private fun result(item: MigrationJobItem): MigrationItemResultView =
        when (val outcome = item.result) {
            null -> {
                if (item.processing) {
                    MigrationItemResultView("processing", "가져오는 중…")
                } else {
                    MigrationItemResultView("pending", "대기 중")
                }
            }

            is MigratePostResult.Imported -> {
                imported(outcome)
            }

            is MigratePostResult.Skipped -> {
                MigrationItemResultView("skipped", "이미 있는 원본 글입니다. 기존 글을 유지했습니다.", postPath(outcome.postId))
            }

            is MigratePostResult.Failed -> {
                MigrationItemResultView("failed", MigrationFailureMessages.result(outcome))
            }
        }

    private fun imported(result: MigratePostResult.Imported): MigrationItemResultView {
        val href = postPath(result.postId)
        if (result.warnings.isEmpty()) return MigrationItemResultView("imported", "초안으로 가져왔습니다.", href)
        return MigrationItemResultView("partial", MigrationFailureMessages.partial(result.warnings), href)
    }

    private fun fields(properties: List<MigrationProperty>): MigrationFieldsView =
        MigrationFieldsView(
            options(properties.filter { it.type in setOf("title", "rich_text") }, setOf("제목", "title", "이름", "name"), "title"),
            options(properties.filter { it.type == "rich_text" }, setOf("요약", "summary")),
            options(properties.filter { it.type in setOf("multi_select", "select") }, setOf("태그", "tags")),
            options(properties.filter { it.type in setOf("date", "created_time") }, setOf("작성일", "작성 시간", "작성시간", "authoredat")),
        )

    private fun options(
        properties: List<MigrationProperty>,
        names: Set<String>,
        preferredType: String? = null,
    ): List<MigrationOptionView> {
        val preferred = properties.firstOrNull { it.type == preferredType } ?: properties.firstOrNull { it.name.lowercase() in names }
        return properties.map { MigrationOptionView(it.id, "${it.name} (${it.type})", it.id == preferred?.id) }
    }

    private val timestamp = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
}
