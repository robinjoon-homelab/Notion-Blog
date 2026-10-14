package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.SyncStateTable
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SyncStateRepository
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailure
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.blog.domain.sync.SyncState
import xyz.robinjoon.notionblog.blog.domain.sync.SyncTarget
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ExposedSyncStateRepository : SyncStateRepository {
    override fun findDue(
        now: Instant,
        limit: Int,
    ): List<SyncState> {
        require(limit >= 0) { "sync state due query limit cannot be negative" }
        if (limit == 0) return emptyList()

        return SyncStateTable
            .selectAll()
            .where { SyncStateTable.refreshAfter lessEq now.asOffsetDateTime() }
            .orderBy(
                SyncStateTable.refreshAfter to SortOrder.ASC,
                SyncStateTable.targetKind to SortOrder.ASC,
                SyncStateTable.targetKey to SortOrder.ASC,
            ).limit(limit)
            .map { row -> row.toSyncState() }
    }

    override fun find(target: SyncTarget): SyncState? {
        val persistedTarget = target.toPersistedTarget()
        return SyncStateTable
            .selectAll()
            .where {
                (SyncStateTable.targetKind eq persistedTarget.kind) and
                    (SyncStateTable.targetKey eq persistedTarget.key)
            }.singleOrNull()
            ?.toSyncState()
    }

    override fun save(state: SyncState) {
        val target = state.target.toPersistedTarget()
        SyncStateTable.upsert(SyncStateTable.targetKind, SyncStateTable.targetKey) {
            it[targetKind] = target.kind
            it[targetKey] = target.key
            it[lastSuccessAt] = state.lastSuccessAt?.asOffsetDateTime()
            it[refreshAfter] = state.refreshAfter.asOffsetDateTime()
            it[failureCount] = state.failureCount
            it[lastErrorKind] = state.lastErrorKind?.name
        }
    }

    override fun delete(target: SyncTarget) {
        val persistedTarget = target.toPersistedTarget()
        SyncStateTable.deleteWhere {
            (targetKind eq persistedTarget.kind) and (targetKey eq persistedTarget.key)
        }
    }

    private fun ResultRow.toSyncState(): SyncState =
        SyncState.restore(
            target = persistedTarget(this[SyncStateTable.targetKind], this[SyncStateTable.targetKey]),
            lastSuccessAt = this[SyncStateTable.lastSuccessAt]?.toInstant(),
            refreshAfter = this[SyncStateTable.refreshAfter].toInstant(),
            failure = toSyncFailure(),
        )

    // The table keeps the count and kind as a pair; a stored half pair is rejected instead of being dropped.
    private fun ResultRow.toSyncFailure(): SyncFailure? {
        val count = this[SyncStateTable.failureCount]
        val kind = this[SyncStateTable.lastErrorKind]?.let(SyncFailureKind::valueOf)
        if (count == 0 && kind == null) {
            return null
        }
        return SyncFailure(count, requireNotNull(kind) { "sync failure count and error kind must be set together" })
    }

    private fun SyncTarget.toPersistedTarget(): PersistedTarget =
        when (this) {
            SyncTarget.SiteConfiguration -> PersistedTarget(SITE_CONFIGURATION, SINGLETON_KEY)
            is SyncTarget.Publication -> PersistedTarget(PUBLICATION, publicationId.value.toString())
            is SyncTarget.Post -> PersistedTarget(POST, postId.value.toString())
        }

    private fun persistedTarget(
        kind: String,
        key: String,
    ): SyncTarget =
        when (kind) {
            SITE_CONFIGURATION -> {
                require(key == SINGLETON_KEY) { "invalid site configuration sync target key: $key" }
                SyncTarget.SiteConfiguration
            }

            PUBLICATION -> {
                SyncTarget.Publication(PublicationId(UUID.fromString(key)))
            }

            POST -> {
                SyncTarget.Post(PostId(UUID.fromString(key)))
            }

            else -> {
                throw IllegalArgumentException("unknown sync target kind: $kind")
            }
        }

    private fun Instant.asOffsetDateTime() = atOffset(ZoneOffset.UTC)

    private data class PersistedTarget(
        val kind: String,
        val key: String,
    )

    private companion object {
        const val SITE_CONFIGURATION = "SITE_CONFIGURATION"
        const val PUBLICATION = "PUBLICATION"
        const val POST = "POST"
        const val SINGLETON_KEY = "singleton"
    }
}
