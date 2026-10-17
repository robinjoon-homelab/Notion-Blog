package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object PostCategoryTable : Table("post_category") {
    val postId = javaUUID("post_id").references(PostTable.postId)
    val categoryId = javaUUID("category_id").references(CategoryTable.categoryId, onDelete = ReferenceOption.RESTRICT)

    override val primaryKey = PrimaryKey(postId, categoryId)
}
