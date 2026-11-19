package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object MigrationJobTable : Table("migration_job") {
    val jobId = javaUUID("job_id")
    val createdAt = timestampWithTimeZone("created_at")
    val dataSourceId = text("data_source_id")
    val titlePropertyId = text("title_property_id")
    val summaryPropertyId = text("summary_property_id")
    val tagsPropertyId = text("tags_property_id")
    val authoredAtPropertyId = text("authored_at_property_id")
    val version = long("version").default(0)
    val paused = bool("paused")
    val leasedPosition = integer("leased_position").nullable()
    val leaseToken = javaUUID("lease_token").nullable()
    val leaseExpiresAt = timestampWithTimeZone("lease_expires_at").nullable()

    override val primaryKey = PrimaryKey(jobId)
}
