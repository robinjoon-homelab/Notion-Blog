package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.MigrationJobItemTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.MigrationJobTable
import xyz.robinjoon.notionblog.blog.application.model.ClaimedMigrationItem
import xyz.robinjoon.notionblog.blog.application.model.MigratePostCommand
import xyz.robinjoon.notionblog.blog.application.model.MigratePostResult
import xyz.robinjoon.notionblog.blog.application.model.MigrationEntry
import xyz.robinjoon.notionblog.blog.application.model.MigrationFields
import xyz.robinjoon.notionblog.blog.application.model.MigrationJob
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobAction
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobLease
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobProgress
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobStorageException
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobSummary
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobUpdates
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.MigrationJobRepository
import java.time.Instant
import java.util.UUID

class ExposedMigrationJobRepository : MigrationJobRepository {
    override fun create(
        job: MigrationJob,
        entries: List<MigrationEntry>,
    ) {
        try {
            insertJob(job)
            insertEntries(job.id, entries)
        } catch (exception: ExposedSQLException) {
            throw MigrationJobStorageException(exception)
        }
    }

    private fun insertJob(job: MigrationJob) {
        MigrationJobTable.insert {
            it[jobId] = job.id
            it[createdAt] = job.createdAt.asOffsetDateTime()
            it[dataSourceId] = job.selection.dataSourceId
            it[titlePropertyId] = job.selection.fields.titlePropertyId
            it[summaryPropertyId] = job.selection.fields.summaryPropertyId
            it[tagsPropertyId] = job.selection.fields.tagsPropertyId
            it[authoredAtPropertyId] = job.selection.fields.authoredAtPropertyId
            it[paused] = job.paused
        }
    }

    private fun insertEntries(
        id: UUID,
        entries: List<MigrationEntry>,
    ) {
        MigrationJobItemTable.batchInsert(entries.withIndex().toList()) { (index, entry) ->
            this[MigrationJobItemTable.jobId] = id
            this[MigrationJobItemTable.position] = index
            this[MigrationJobItemTable.sourceId] = entry.sourceDocument.sourceId.value
            this[MigrationJobItemTable.externalId] = entry.sourceDocument.externalId
            this[MigrationJobItemTable.title] = entry.title.replace('\u0000', '\uFFFD')
        }
    }

    override fun recent(
        limit: Int,
        offset: Long,
        now: Instant,
    ): List<MigrationJobSummary> = MigrationJobQueries.recent(limit, offset, now)

    override fun find(
        id: UUID,
        now: Instant,
    ): MigrationJobProgress? = MigrationJobQueries.find(id, now)

    override fun updates(
        id: UUID,
        after: Long,
        now: Instant,
    ): MigrationJobUpdates? = MigrationJobQueries.updates(id, after, now)

    override fun claim(lease: MigrationJobLease): ClaimedMigrationItem? {
        releaseExpiredPausedClaim(lease.at)
        val job = lockClaimableJob(lease.at) ?: return null
        val id = job[MigrationJobTable.jobId]
        val item = nextPendingItem(id) ?: return null
        val position = item[MigrationJobItemTable.position]
        MigrationJobTable.update({ MigrationJobTable.jobId eq id }) {
            it[leasedPosition] = position
            it[leaseToken] = lease.token
            it[leaseExpiresAt] = lease.expiresAt.asOffsetDateTime()
        }
        return ClaimedMigrationItem(
            id,
            position,
            lease.token,
            MigratePostCommand(job.selection(), item.migrationSourceDocument()),
        )
    }

    override fun renewClaim(
        claim: ClaimedMigrationItem,
        expiresAt: Instant,
    ) {
        val expiry = expiresAt.asOffsetDateTime()
        try {
            MigrationJobTable.update({ matchesClaim(claim) and (MigrationJobTable.leaseExpiresAt less expiry) }) {
                it[leaseExpiresAt] = expiry
            }
        } catch (exception: ExposedSQLException) {
            // ScheduledExecutorService will cancel every future renewal if a checked database error escapes.
            throw IllegalStateException("migration lease renewal failed", exception)
        }
    }

    override fun lockClaim(claim: ClaimedMigrationItem): Boolean = lockedClaimVersion(claim) != null

    private fun lockedClaimVersion(claim: ClaimedMigrationItem): Long? =
        MigrationJobTable
            .select(MigrationJobTable.version)
            .where { matchesClaim(claim) }
            .forUpdate()
            .singleOrNull()
            ?.get(MigrationJobTable.version)

    private fun matchesClaim(claim: ClaimedMigrationItem) =
        (MigrationJobTable.jobId eq claim.jobId) and
            (MigrationJobTable.leasedPosition eq claim.position) and
            (MigrationJobTable.leaseToken eq claim.token)

    override fun complete(
        claim: ClaimedMigrationItem,
        result: MigratePostResult,
    ) {
        val nextVersion = lockedClaimVersion(claim)?.plus(1) ?: return
        clearLease(claim.jobId, nextVersion)
        val completed =
            MigrationJobItemTable.update({
                (MigrationJobItemTable.jobId eq claim.jobId) and
                    (MigrationJobItemTable.position eq claim.position) and MigrationJobItemTable.resultKind.isNull()
            }) {
                it[changedVersion] = nextVersion
                MigrationJobItemResult.write(it, result)
            }
        check(completed == 1) { "leased migration item must be pending" }
    }

    private fun clearLease(
        jobId: UUID,
        newVersion: Long,
    ) {
        MigrationJobTable.update({ MigrationJobTable.jobId eq jobId }) {
            it[version] = newVersion
            it[leasedPosition] = null
            it[leaseToken] = null
            it[leaseExpiresAt] = null
        }
    }

    private fun releaseExpiredPausedClaim(now: Instant) {
        val job =
            MigrationJobTable
                .select(MigrationJobTable.jobId, MigrationJobTable.version, MigrationJobTable.leasedPosition)
                .where {
                    (MigrationJobTable.paused eq true) and (MigrationJobTable.leaseExpiresAt lessEq now.asOffsetDateTime())
                }.orderBy(MigrationJobTable.leaseExpiresAt to SortOrder.ASC)
                .limit(1)
                .forUpdate(skipLocked)
                .singleOrNull() ?: return
        val id = job[MigrationJobTable.jobId]
        val position = requireNotNull(job[MigrationJobTable.leasedPosition])
        val nextVersion = job[MigrationJobTable.version] + 1
        clearLease(id, nextVersion)
        MigrationJobItemTable.update({
            (MigrationJobItemTable.jobId eq id) and (MigrationJobItemTable.position eq position)
        }) { it[changedVersion] = nextVersion }
    }

    override fun change(
        jobId: UUID,
        action: MigrationJobAction,
    ): Boolean {
        if (action == MigrationJobAction.RETRY_FAILED) return retryFailed(jobId)
        return MigrationJobTable.update({ MigrationJobTable.jobId eq jobId }) {
            it[paused] = action == MigrationJobAction.PAUSE
        } > 0
    }

    private fun retryFailed(jobId: UUID): Boolean {
        val job =
            MigrationJobTable
                .select(MigrationJobTable.version)
                .where { MigrationJobTable.jobId eq jobId }
                .forUpdate()
                .singleOrNull() ?: return false
        if (nextPendingItem(jobId) != null) return true
        val nextVersion = job[MigrationJobTable.version] + 1
        val retried =
            MigrationJobItemTable.update({
                (MigrationJobItemTable.jobId eq jobId) and (MigrationJobItemTable.resultKind eq MigrationJobItemResult.FAILED)
            }) {
                it[changedVersion] = nextVersion
                MigrationJobItemResult.clear(it)
            }
        if (retried > 0) {
            MigrationJobTable.update({ MigrationJobTable.jobId eq jobId }) {
                it[paused] = false
                it[version] = nextVersion
            }
        }
        return true
    }

    private fun lockClaimableJob(now: Instant): ResultRow? {
        val pendingItems =
            MigrationJobItemTable
                .select(MigrationJobItemTable.jobId)
                .where { (MigrationJobItemTable.jobId eq MigrationJobTable.jobId) and MigrationJobItemTable.resultKind.isNull() }
        return MigrationJobTable
            .selectAll()
            .where {
                (MigrationJobTable.paused eq false) and
                    (MigrationJobTable.leaseExpiresAt.isNull() or (MigrationJobTable.leaseExpiresAt lessEq now.asOffsetDateTime())) and
                    exists(pendingItems)
            }.orderBy(MigrationJobTable.createdAt to SortOrder.ASC, MigrationJobTable.jobId to SortOrder.ASC)
            .limit(1)
            .forUpdate(skipLocked)
            .singleOrNull()
    }

    private fun nextPendingItem(id: UUID): ResultRow? =
        MigrationJobItemTable
            .selectAll()
            .where { (MigrationJobItemTable.jobId eq id) and MigrationJobItemTable.resultKind.isNull() }
            .orderBy(MigrationJobItemTable.position to SortOrder.ASC)
            .limit(1)
            .singleOrNull()

    private fun ResultRow.selection(): MigrationSelection =
        MigrationSelection(
            this[MigrationJobTable.dataSourceId],
            MigrationFields(
                this[MigrationJobTable.titlePropertyId],
                this[MigrationJobTable.summaryPropertyId],
                this[MigrationJobTable.tagsPropertyId],
                this[MigrationJobTable.authoredAtPropertyId],
            ),
        )

    private companion object {
        val skipLocked = ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED)
    }
}
