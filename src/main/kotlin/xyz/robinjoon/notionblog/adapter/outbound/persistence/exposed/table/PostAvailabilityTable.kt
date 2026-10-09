package xyz.robinjoon.notionblog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object PostAvailabilityTable : Table("post_availability") {
    val postId = javaUUID("post_id").references(PostTable.postId)
    val status = text("status")
    val confirmedAt = timestampWithTimeZone("confirmed_at")

    override val primaryKey = PrimaryKey(postId)
}
