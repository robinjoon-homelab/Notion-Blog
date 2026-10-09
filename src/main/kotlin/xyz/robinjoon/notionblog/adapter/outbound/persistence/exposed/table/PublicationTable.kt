package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object PublicationTable : Table("publication") {
    val publicationId = javaUUID("publication_id")
    val rootPostId = optReference("root_post_id", PostTable.postId)
    val activeRevisionId = javaUUID("active_revision_id").nullable()

    override val primaryKey = PrimaryKey(publicationId)
}
