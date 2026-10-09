package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

object PostSnapshotTable : Table("post_snapshot") {
    val postId = javaUUID("post_id").references(PostTable.postId)
    val snapshotJson = jsonb("snapshot_json", { value: String -> value }, { value -> value })
    val sourceRevision = text("source_revision")
    val capturedAt = timestampWithTimeZone("captured_at")

    override val primaryKey = PrimaryKey(postId)
}
