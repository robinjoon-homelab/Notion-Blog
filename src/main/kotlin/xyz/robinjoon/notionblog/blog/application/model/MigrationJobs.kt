package xyz.robinjoon.notionblog.blog.application.model

import java.time.Instant
import java.util.UUID

data class MigrationJob(
    val id: UUID,
    val createdAt: Instant,
    val selection: MigrationSelection,
    val paused: Boolean = false,
)

data class MigrationJobSummary(
    val id: UUID,
    val createdAt: Instant,
    val total: Int,
    val remaining: Int,
    val paused: Boolean,
    val processing: Boolean = false,
)

data class MigrationJobHistory(
    val jobs: List<MigrationJobSummary>,
    val hasNext: Boolean,
)

data class MigrationJobProgress(
    val summary: MigrationJobSummary,
    val items: List<MigrationJobItem>,
    val version: Long = 0,
)

data class MigrationJobCounts(
    val imported: Int = 0,
    val partial: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
)

data class MigrationJobUpdates(
    val summary: MigrationJobSummary,
    val version: Long,
    val counts: MigrationJobCounts,
    val items: List<MigrationJobItem>,
)

data class MigrationJobItem(
    val position: Int,
    val entry: MigrationEntry,
    val processing: Boolean = false,
    val result: MigratePostResult? = null,
)

data class ClaimedMigrationItem(
    val jobId: UUID,
    val position: Int,
    val token: UUID,
    val command: MigratePostCommand,
)

data class MigrationJobLease(
    val token: UUID,
    val at: Instant,
    val expiresAt: Instant,
)

enum class MigrationJobAction { PAUSE, RESUME, RETRY_FAILED }

class MigrationJobStorageException(
    cause: Throwable,
) : RuntimeException("migration job storage is unavailable", cause)
