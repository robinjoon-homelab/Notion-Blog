package xyz.robinjoon.notionblog.application.port.output.persistence

import xyz.robinjoon.notionblog.domain.sync.SyncState
import xyz.robinjoon.notionblog.domain.sync.SyncTarget
import java.time.Instant

interface SyncStateRepository {
    fun findDue(
        now: Instant,
        limit: Int,
    ): List<SyncState>

    fun find(target: SyncTarget): SyncState?

    fun save(state: SyncState)

    fun delete(target: SyncTarget)
}
