package xyz.robinjoon.notionblog.blog.adapter.inbound.web.view

// 대표 이미지는 항상 값이 있다. 첫 공개 글의 표지가 없거나 사본이 없으면 내장 썸네일이다.
data class SeriesCardView(
    val href: String,
    val title: String,
    val description: String?,
    val postCount: Int,
    val thumbnailHref: String,
)

data class SeriesListPageView(
    val chrome: PageChromeView,
    val tabs: List<HomeTabView>,
    val cards: List<SeriesCardView>,
    val empty: EmptyStateView?,
)

// 요약·날짜는 홈 표시 설정을 따라 숨기면 null이다.
data class SeriesEntryView(
    val ordinal: Int,
    val href: String,
    val title: String,
    val summary: String?,
    val publishedOn: String?,
)

data class SeriesPageView(
    val chrome: PageChromeView,
    val tabs: List<HomeTabView>,
    val title: String,
    val description: String?,
    val entries: List<SeriesEntryView>,
)

data class SeriesNavEntryView(
    val ordinal: Int,
    val href: String,
    val title: String,
    val current: Boolean,
)

// 글 화면의 시리즈 목차와 이전·다음이다. 순번과 이동 대상은 공개 글만으로 계산된 값이다.
data class SeriesNavView(
    val title: String,
    val href: String,
    val position: Int,
    val entries: List<SeriesNavEntryView>,
    val previous: SeriesNavEntryView?,
    val next: SeriesNavEntryView?,
)
