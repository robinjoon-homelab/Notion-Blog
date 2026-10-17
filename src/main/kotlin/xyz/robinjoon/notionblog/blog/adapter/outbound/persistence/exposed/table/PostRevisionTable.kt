package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

object PostRevisionTable : Table("post_revision") {
    val revisionId = javaUUID("revision_id")
    val postId = javaUUID("post_id").references(PostTable.postId)
    val title = text("title")
    val summary = text("summary").nullable()
    val tagsJson = jsonb("tags_json", { value: String -> value }, { value -> value })
    val coverJson = jsonb("cover_json", { value: String -> value }, { value -> value }).nullable()
    val contentJson = jsonb("content_json", { value: String -> value }, { value -> value })
    val sourceRevision = text("source_revision")
    val capturedAt = timestampWithTimeZone("captured_at")

    override val primaryKey = PrimaryKey(revisionId)

    init {
        uniqueIndex("post_revision_post_revision_unique", postId, revisionId)
        uniqueIndex("post_revision_post_source_revision_unique", postId, sourceRevision)
    }
}
