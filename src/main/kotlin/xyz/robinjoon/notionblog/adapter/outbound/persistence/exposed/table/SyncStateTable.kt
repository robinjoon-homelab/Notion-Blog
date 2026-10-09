package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object SyncStateTable : Table("sync_state") {
    val targetKind = text("target_kind")
    val targetKey = text("target_key")
    val lastSuccessAt = timestampWithTimeZone("last_success_at").nullable()
    val refreshAfter = timestampWithTimeZone("refresh_after")
    val failureCount = integer("failure_count")
    val lastErrorKind = text("last_error_kind").nullable()

    override val primaryKey = PrimaryKey(targetKind, targetKey)
}
