package xyz.robinjoon.notionblog.blog.application.service

import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.domain.sync.RefreshPolicy
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.blog.domain.sync.SyncState
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import java.time.Instant

// 호출자의 트랜잭션 안에서 동기화 결과를 기록한다.
class SyncStateRecorder(
    private val syncStates: SyncStateRepository,
    private val refreshPolicy: RefreshPolicy,
) {
    fun recordSuccess(
        target: SyncTarget,
        now: Instant,
    ) {
        val refreshAfter = refreshPolicy.nextSuccessfulRefreshAt(now)
        syncStates.save(current(target, now).recordSuccess(now, refreshAfter))
    }

    fun recordFailure(
        target: SyncTarget,
        kind: SyncFailureKind,
        now: Instant,
    ) {
        val current = current(target, now)
        val refreshAfter = refreshPolicy.nextFailureRefreshAt(now, Math.addExact(current.failureCount, 1))
        syncStates.save(current.recordFailure(kind, refreshAfter))
    }

    // 기존 일정을 유지하므로 다시 등록된 글도 실패 이력을 유지한다.
    fun scheduleNow(
        target: SyncTarget,
        now: Instant,
    ) {
        if (syncStates.find(target) == null) {
            syncStates.save(SyncState.schedule(target, now))
        }
    }

    fun cancel(target: SyncTarget) = syncStates.delete(target)

    private fun current(
        target: SyncTarget,
        now: Instant,
    ): SyncState = syncStates.find(target) ?: SyncState.schedule(target, now)
}
