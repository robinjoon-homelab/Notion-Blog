package xyz.robinjoon.notionblog.blog.domain.sync

import xyz.robinjoon.notionblog.domain.AggregateRoot
import java.time.Instant

// The refresh schedule of one synchronization target. A failure record exists only while consecutive failures do.
@AggregateRoot
@ConsistentCopyVisibility
data class SyncState private constructor(
    val target: SyncTarget,
    val lastSuccessAt: Instant?,
    val refreshAfter: Instant,
    val failure: SyncFailure?,
) {
    val failureCount: Int
        get() = failure?.count ?: 0

    val lastErrorKind: SyncFailureKind?
        get() = failure?.lastKind

    fun recordSuccess(
        completedAt: Instant,
        refreshAfter: Instant,
    ): SyncState =
        copy(
            lastSuccessAt = completedAt,
            refreshAfter = refreshAfter,
            failure = null,
        )

    fun recordFailure(
        kind: SyncFailureKind,
        refreshAfter: Instant,
    ): SyncState =
        copy(
            refreshAfter = refreshAfter,
            failure = SyncFailure(Math.addExact(failureCount, 1), kind),
        )

    companion object {
        // A target that has never run. Recording its first result replaces the schedule.
        fun schedule(
            target: SyncTarget,
            refreshAfter: Instant,
        ): SyncState = SyncState(target, null, refreshAfter, null)

        fun restore(
            target: SyncTarget,
            lastSuccessAt: Instant?,
            refreshAfter: Instant,
            failure: SyncFailure?,
        ): SyncState = SyncState(target, lastSuccessAt, refreshAfter, failure)
    }
}

// Consecutive failures since the last success and the kind of the latest one.
data class SyncFailure(
    val count: Int,
    val lastKind: SyncFailureKind,
) {
    init {
        require(count > 0) { "sync failure count must be positive" }
    }
}
