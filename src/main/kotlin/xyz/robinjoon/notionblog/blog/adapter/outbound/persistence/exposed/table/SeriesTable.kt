package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object SeriesTable : Table("series") {
    val seriesId = javaUUID("series_id")
    val title = text("title")
    val description = text("description")

    override val primaryKey = PrimaryKey(seriesId)
}
