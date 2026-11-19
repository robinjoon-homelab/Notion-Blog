package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.ClaimedMigrationItem
import xyz.robinjoon.notionblog.blog.application.model.MigratePostResult
import xyz.robinjoon.notionblog.blog.application.model.MigrationJob
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobAction
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobHistory
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobLease
import xyz.robinjoon.notionblog.blog.application.model.MigrationPlan
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.MigrationJobRepository
import java.time.Clock
import java.time.Duration
import java.util.UUID

// 외부 조회와 파일 다운로드는 아래 트랜잭션 사이에서 실행한다.
@Transactional(rollbackFor = [Exception::class])
class MigrationJobStateService(
    private val jobs: MigrationJobRepository,
    private val clock: Clock,
) {
    fun create(plan: MigrationPlan): UUID {
        val job = MigrationJob(UUID.randomUUID(), clock.instant(), plan.selection)
        jobs.create(job, plan.entries)
        return job.id
    }

    @Transactional(readOnly = true)
    fun history(page: Int): MigrationJobHistory {
        require(page > 0) { "migration history pages start at one" }
        val offset = (page.toLong() - 1) * HISTORY_PAGE_SIZE
        val window = jobs.recent(HISTORY_PAGE_SIZE + 1, offset, clock.instant())
        return MigrationJobHistory(window.take(HISTORY_PAGE_SIZE), window.size > HISTORY_PAGE_SIZE)
    }

    @Transactional(readOnly = true)
    fun progress(id: UUID) = jobs.find(id, clock.instant())

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun updates(
        id: UUID,
        after: Long,
    ) = jobs.updates(id, after, clock.instant())

    fun claim(): ClaimedMigrationItem? {
        val now = clock.instant()
        return jobs.claim(MigrationJobLease(UUID.randomUUID(), now, now.plus(leaseDuration)))
    }

    fun renew(item: ClaimedMigrationItem) = jobs.renewClaim(item, clock.instant().plus(leaseDuration))

    fun complete(
        item: ClaimedMigrationItem,
        result: MigratePostResult,
    ) = jobs.complete(item, result)

    fun change(
        id: UUID,
        action: MigrationJobAction,
    ) = jobs.change(id, action)

    private companion object {
        const val HISTORY_PAGE_SIZE = 20
        val leaseDuration = Duration.ofMinutes(5)
    }
}
