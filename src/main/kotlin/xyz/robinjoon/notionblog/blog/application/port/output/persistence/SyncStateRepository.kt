package xyz.robinjoon.notionblog.blog.application.port.output.persistence

import xyz.robinjoon.notionblog.blog.domain.sync.SyncState
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import java.time.Instant

interface SyncStateRepository {
    fun findDue(
        now: Instant,
        limit: Int,
    ): List<SyncState>

    fun find(target: SyncTarget): SyncState?

    fun findAll(targets: Set<SyncTarget>): Map<SyncTarget, SyncState>

    fun save(state: SyncState)

    fun delete(target: SyncTarget)
}
