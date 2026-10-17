package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object PostPublicationTable : Table("post_publication") {
    val postId = javaUUID("post_id").references(PostTable.postId)
    val visibility = text("visibility")
    val latestRevisionId = javaUUID("latest_revision_id").nullable()
    val publishedRevisionId = javaUUID("published_revision_id").nullable()
    val archived = bool("archived")
    val generation = long("generation")
    val changedAt = timestampWithTimeZone("changed_at")

    override val primaryKey = PrimaryKey(postId)

    init {
        foreignKey(postId to PostRevisionTable.postId, latestRevisionId to PostRevisionTable.revisionId)
        foreignKey(postId to PostRevisionTable.postId, publishedRevisionId to PostRevisionTable.revisionId)
    }
}
