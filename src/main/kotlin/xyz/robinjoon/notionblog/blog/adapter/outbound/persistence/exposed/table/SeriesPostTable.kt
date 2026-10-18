package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

// 글 하나는 시리즈 하나에만 속하고 한 시리즈 안의 위치는 겹치지 않는다.
object SeriesPostTable : Table("series_post") {
    val seriesId = javaUUID("series_id").references(SeriesTable.seriesId)
    val postId = javaUUID("post_id").references(PostTable.postId).uniqueIndex("series_post_post_unique")
    val position = integer("position")

    override val primaryKey = PrimaryKey(seriesId, postId)

    init {
        uniqueIndex("series_post_position_unique", seriesId, position)
    }
}
