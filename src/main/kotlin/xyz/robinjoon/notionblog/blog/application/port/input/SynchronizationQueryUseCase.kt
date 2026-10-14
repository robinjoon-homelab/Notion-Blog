package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import java.time.Instant

interface SynchronizationQueryUseCase {
    fun findDueTargets(
        now: Instant,
        limit: Int,
    ): List<SyncTarget>
}
