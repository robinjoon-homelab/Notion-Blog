package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table

import org.jetbrains.exposed.v1.core.Table

object HomeConfigurationTable : Table("home_configuration") {
    val homeId = short("home_id")
    val layout = text("layout")
    val postOrder = text("post_order")
    val pageSize = integer("page_size")
    val showSummary = bool("show_summary")
    val showDate = bool("show_date")
    val showCategories = bool("show_categories")

    override val primaryKey = PrimaryKey(homeId)
}
