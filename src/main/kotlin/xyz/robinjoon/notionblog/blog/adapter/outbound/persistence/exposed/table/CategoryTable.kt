package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object CategoryTable : Table("category") {
    val categoryId = javaUUID("category_id")
    val name = text("name")
    val normalizedName = text("normalized_name").uniqueIndex()
    val position = integer("position")

    override val primaryKey = PrimaryKey(categoryId)
}
