package xyz.robinjoon.notionblog.application.port.input

import xyz.robinjoon.notionblog.domain.sync.SyncTarget
import java.time.Instant

interface SynchronizationQueryUseCase {
    fun findDueTargets(
        now: Instant,
        limit: Int,
    ): List<SyncTarget>
}
