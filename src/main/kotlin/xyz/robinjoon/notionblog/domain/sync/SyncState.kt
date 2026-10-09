package xyz.robinjoon.notionblog.domain.sync

import java.time.Instant

data class SyncState(
    val target: SyncTarget,
    val lastSuccessAt: Instant?,
    val refreshAfter: Instant,
    val failureCount: Int,
    val lastErrorKind: SyncFailureKind?,
) {
    init {
        require(failureCount >= 0) { "sync failure count cannot be negative" }
        require((failureCount == 0) == (lastErrorKind == null)) {
            "sync failure count and error kind must be set together"
        }
    }

    fun recordSuccess(
        completedAt: Instant,
        refreshAfter: Instant,
    ): SyncState =
        copy(
            lastSuccessAt = completedAt,
            refreshAfter = refreshAfter,
            failureCount = 0,
            lastErrorKind = null,
        )

    fun recordFailure(
        kind: SyncFailureKind,
        refreshAfter: Instant,
    ): SyncState =
        copy(
            refreshAfter = refreshAfter,
            failureCount = Math.addExact(failureCount, 1),
            lastErrorKind = kind,
        )
}
