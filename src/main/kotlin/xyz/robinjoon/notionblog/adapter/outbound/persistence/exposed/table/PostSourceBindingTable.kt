package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object PostSourceBindingTable : Table("post_source_binding") {
    val sourceId = text("source_id")
    val externalId = text("external_id")
    val postId = javaUUID("post_id").references(PostTable.postId).uniqueIndex()

    override val primaryKey = PrimaryKey(sourceId, externalId)
}
