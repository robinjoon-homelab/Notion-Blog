package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object PostMigrationMappingTable : Table("post_migration_mapping") {
    val postId = javaUUID("post_id").references(PostPublicationTable.postId)
    val dataSourceId = text("data_source_id")
    val titlePropertyId = text("title_property_id")
    val summaryPropertyId = text("summary_property_id")
    val tagsPropertyId = text("tags_property_id")
    val authoredAtPropertyId = text("authored_at_property_id")

    override val primaryKey = PrimaryKey(postId)
}
