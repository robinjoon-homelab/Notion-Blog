package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object OwnedMediaTable : Table("owned_media") {
    val mediaKey = text("media_key")
    val contentType = text("content_type")
    val byteSize = long("byte_size")
    val bytes = binary("bytes")
    val storedAt = timestampWithTimeZone("stored_at")

    override val primaryKey = PrimaryKey(mediaKey)
}
