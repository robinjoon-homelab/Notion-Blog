package xyz.robinjoon.notionblog.blog.adapter.inbound.web.view

data class AdminSeriesRowView(
    val id: String,
    val title: String,
    val description: String,
    val postCount: Int,
    val publicPostCount: Int,
)

data class AdminSeriesListView(
    val rows: List<AdminSeriesRowView>,
    val notice: NoticeView?,
)

// currentSeries는 후보 글이 지금 속한 다른 시리즈의 제목이다. 저장하면 이 시리즈로 옮겨진다.
data class SeriesMemberView(
    val postId: String,
    val title: String,
    val badges: List<BadgeView>,
    val currentSeries: String?,
)

// members는 시리즈 순서이고 candidates는 이 시리즈에 없고 보관되지 않은 글이다.
data class AdminSeriesEditView(
    val id: String,
    val title: String,
    val description: String,
    val members: List<SeriesMemberView>,
    val candidates: List<SeriesMemberView>,
    val notice: NoticeView?,
)
