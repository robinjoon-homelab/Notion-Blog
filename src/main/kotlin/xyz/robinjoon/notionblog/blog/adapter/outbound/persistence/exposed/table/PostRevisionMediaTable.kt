package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object PostRevisionMediaTable : Table("post_revision_media") {
    val revisionId = javaUUID("revision_id").references(PostRevisionTable.revisionId)
    val sourceKey = text("source_key")
    val sourceUrl = text("source_url")
    val expiresAt = timestampWithTimeZone("expires_at").nullable()
    val mediaKey = text("media_key").references(OwnedMediaTable.mediaKey).nullable()

    override val primaryKey = PrimaryKey(revisionId, sourceKey)
}
