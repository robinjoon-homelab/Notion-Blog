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

data class SeriesOptionView(
    val id: String,
    val title: String,
    val postCount: Int,
    val selected: Boolean,
)

// existingName은 이름이 같은 기존 태그가 있을 때 그 이름이다. 전환하면 새로 만들지 않고 재사용한다.
// tooLong은 태그 이름 길이 제한을 넘어 옮길 수 없는 후보다.
data class SourceTagOptionView(
    val name: String,
    val existingName: String?,
    val assigned: Boolean,
    val tooLong: Boolean,
)

data class AdminPostCategoriesView(
    val postId: String,
    val title: String,
    val options: List<CategoryOptionView>,
    val series: List<SeriesOptionView>,
    val sourceTags: List<SourceTagOptionView>,
    val notice: NoticeView?,
)
