package xyz.robinjoon.notionblog.blog.adapter.inbound.web.view

import xyz.robinjoon.notionblog.blog.domain.home.HomePostDetails

data class ChoiceView(
    val value: String,
    val label: String,
    val description: String,
    val selected: Boolean,
)

data class AdminHomeView(
    val layouts: List<ChoiceView>,
    val orders: List<ChoiceView>,
    val pageSize: Int,
    val details: HomePostDetails,
    val notice: NoticeView?,
)

data class AdminCategoryRowView(
    val id: String,
    val name: String,
    val position: Int,
    val assignedPosts: Int,
    val deletable: Boolean,
)

data class AdminCategoriesView(
    val rows: List<AdminCategoryRowView>,
    val notice: NoticeView?,
)

data class CategoryOptionView(
    val id: String,
    val name: String,
    val assignedPosts: Int,
    val checked: Boolean,
)

data class AdminPostCategoriesView(
    val postId: String,
    val title: String,
    val options: List<CategoryOptionView>,
    val notice: NoticeView?,
)
