package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object MigrationJobItemTable : Table("migration_job_item") {
    val jobId = javaUUID("job_id").references(MigrationJobTable.jobId)
    val position = integer("position")
    val sourceId = text("source_id")
    val externalId = text("external_id")
    val title = text("source_title")
    val changedVersion = long("changed_version").default(0)
    val resultKind = text("result_kind").nullable()
    val resultPostId = javaUUID("result_post_id").nullable()
    val resultFailureKind = text("result_failure_kind").nullable()
    val resultDiagnostics = text("result_diagnostics").nullable()

    init {
        uniqueIndex(jobId, sourceId, externalId)
        index("migration_job_item_changed_idx", false, jobId, changedVersion)
        index("migration_job_item_kind_idx", false, jobId, resultKind)
    }

    override val primaryKey = PrimaryKey(jobId, position)
}
