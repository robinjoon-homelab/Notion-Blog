package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object PublicationMemberTable : Table("publication_member") {
    val revisionId = javaUUID("revision_id").references(PublicationRevisionTable.revisionId)
    val postId = javaUUID("post_id").references(PostTable.postId)
    val parentPostId = javaUUID("parent_post_id").nullable()
    val depth = integer("depth")

    override val primaryKey = PrimaryKey(revisionId, postId)
}
