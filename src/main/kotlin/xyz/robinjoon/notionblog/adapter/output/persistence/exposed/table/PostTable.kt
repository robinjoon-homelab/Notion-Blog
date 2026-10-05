package xyz.robinjoon.notionblog.adapter.output.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object PostTable : Table("post") {
    val postId = javaUUID("post_id")
    val title = text("title")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val firstPublishedAt = timestampWithTimeZone("first_published_at").nullable()

    override val primaryKey = PrimaryKey(postId)
}
