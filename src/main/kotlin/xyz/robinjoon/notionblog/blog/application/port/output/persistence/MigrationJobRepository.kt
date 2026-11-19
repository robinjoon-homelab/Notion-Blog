package xyz.robinjoon.notionblog.blog.application.port.output.persistence

import xyz.robinjoon.notionblog.blog.application.model.ClaimedMigrationItem
import xyz.robinjoon.notionblog.blog.application.model.MigratePostResult
import xyz.robinjoon.notionblog.blog.application.model.MigrationEntry
import xyz.robinjoon.notionblog.blog.application.model.MigrationJob
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobAction
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobLease
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobProgress
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobSummary
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobUpdates
import java.time.Instant
import java.util.UUID

interface MigrationJobRepository {
    fun create(
        job: MigrationJob,
        entries: List<MigrationEntry>,
    )

    fun recent(
        limit: Int,
        offset: Long,
        now: Instant,
    ): List<MigrationJobSummary>

    fun find(
        id: UUID,
        now: Instant,
    ): MigrationJobProgress?

    fun updates(
        id: UUID,
        after: Long,
        now: Instant,
    ): MigrationJobUpdates?

    fun claim(lease: MigrationJobLease): ClaimedMigrationItem?

    fun renewClaim(
        claim: ClaimedMigrationItem,
        expiresAt: Instant,
    )

    // 토큰이 교체됐으면 저장할 수 없다. 유효한 작업 행의 잠금은 호출자의 쓰기 트랜잭션까지 유지한다.
    fun lockClaim(claim: ClaimedMigrationItem): Boolean

    fun complete(
        claim: ClaimedMigrationItem,
        result: MigratePostResult,
    )

    fun change(
        jobId: UUID,
        action: MigrationJobAction,
    ): Boolean
}
