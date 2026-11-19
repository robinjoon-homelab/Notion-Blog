package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.MigrationJobItemTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.MigrationJobTable
import xyz.robinjoon.notionblog.blog.application.model.MigrationEntry
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobCounts
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobItem
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobProgress
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobSummary
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobUpdates
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import java.time.Instant
import java.util.UUID

internal object MigrationJobQueries {
    fun recent(
        limit: Int,
        offset: Long,
        now: Instant,
    ): List<MigrationJobSummary> {
        require(limit >= 0) { "migration job query limit cannot be negative" }
        require(offset >= 0) { "migration job query offset cannot be negative" }
        if (limit == 0) return emptyList()
        val total = MigrationJobItemTable.jobId.count()
        val completed = MigrationJobItemTable.resultKind.count()
        val jobColumns =
            listOf(MigrationJobTable.jobId, MigrationJobTable.createdAt, MigrationJobTable.paused, MigrationJobTable.leaseExpiresAt)
        return jobsWithItems()
            .select(jobColumns + listOf(total, completed))
            .groupBy(*jobColumns.toTypedArray())
            .orderBy(MigrationJobTable.createdAt to SortOrder.DESC, MigrationJobTable.jobId to SortOrder.DESC)
            .limit(limit)
            .offset(offset)
            .map { row -> row.summary(row[total].toInt(), (row[total] - row[completed]).toInt(), row.processing(now)) }
    }

    fun find(
        id: UUID,
        now: Instant,
    ): MigrationJobProgress? {
        val rows =
            jobsWithItems()
                .selectAll()
                .where { MigrationJobTable.jobId eq id }
                .orderBy(MigrationJobItemTable.position to SortOrder.ASC)
                .toList()
        val job = rows.firstOrNull() ?: return null
        val leasedPosition = job[MigrationJobTable.leasedPosition].takeIf { job.processing(now) }
        val items = rows.filter { it.getOrNull(MigrationJobItemTable.position) != null }.map { it.item(leasedPosition) }
        val summary = job.summary(items.size, items.count { it.result == null }, leasedPosition != null)
        return MigrationJobProgress(summary, items, job[MigrationJobTable.version])
    }

    fun updates(
        id: UUID,
        after: Long,
        now: Instant,
    ): MigrationJobUpdates? {
        val job = MigrationJobTable.selectAll().where { MigrationJobTable.jobId eq id }.singleOrNull() ?: return null
        val version = job[MigrationJobTable.version]
        val leasedPosition = job[MigrationJobTable.leasedPosition]
        val activePosition = leasedPosition.takeIf { job.processing(now) }
        val counts = countsByKind(id)
        val summary = job.summary(counts.values.sum(), counts[null] ?: 0, activePosition != null)
        val query = MigrationJobItemTable.selectAll().where { MigrationJobItemTable.jobId eq id }
        if (after in 0..version) {
            query.andWhere {
                val changed = MigrationJobItemTable.changedVersion greater after
                if (leasedPosition == null) changed else changed or (MigrationJobItemTable.position eq leasedPosition)
            }
        }
        val items = query.orderBy(MigrationJobItemTable.position to SortOrder.ASC).map { it.item(activePosition) }
        return MigrationJobUpdates(
            summary,
            version,
            MigrationJobCounts(
                imported = counts[MigrationJobItemResult.IMPORTED] ?: 0,
                partial = counts[MigrationJobItemResult.PARTIAL] ?: 0,
                skipped = counts[MigrationJobItemResult.SKIPPED] ?: 0,
                failed = counts[MigrationJobItemResult.FAILED] ?: 0,
            ),
            items,
        )
    }

    private fun countsByKind(id: UUID): Map<String?, Int> {
        val count = MigrationJobItemTable.jobId.count()
        return MigrationJobItemTable
            .select(MigrationJobItemTable.resultKind, count)
            .where { MigrationJobItemTable.jobId eq id }
            .groupBy(MigrationJobItemTable.resultKind)
            .associate { it[MigrationJobItemTable.resultKind] to it[count].toInt() }
    }

    private fun jobsWithItems() =
        MigrationJobTable.join(MigrationJobItemTable, JoinType.LEFT, MigrationJobTable.jobId, MigrationJobItemTable.jobId)

    private fun ResultRow.processing(now: Instant): Boolean {
        val expiry = this[MigrationJobTable.leaseExpiresAt] ?: return false
        // A paused job stays draining until the current writer commits or the expired token is fenced.
        return this[MigrationJobTable.paused] || expiry.toInstant().isAfter(now)
    }

    private fun ResultRow.item(leasedPosition: Int?): MigrationJobItem {
        val position = this[MigrationJobItemTable.position]
        return MigrationJobItem(
            position,
            MigrationEntry(migrationSourceDocument(), this[MigrationJobItemTable.title]),
            processing = position == leasedPosition,
            result = MigrationJobItemResult.read(this),
        )
    }

    private fun ResultRow.summary(
        total: Int,
        remaining: Int,
        processing: Boolean,
    ): MigrationJobSummary =
        MigrationJobSummary(
            this[MigrationJobTable.jobId],
            this[MigrationJobTable.createdAt].toInstant(),
            total,
            remaining,
            this[MigrationJobTable.paused],
            processing,
        )
}

internal fun ResultRow.migrationSourceDocument(): SourceDocumentRef =
    SourceDocumentRef(SourceId(this[MigrationJobItemTable.sourceId]), this[MigrationJobItemTable.externalId])
