package xyz.robinjoon.notionblog.blog.adapter.inbound.web.view

// 공개 글·목록·관리 미리보기가 같은 사이트 틀(메타데이터, 신뢰된 자산, 머리글·바닥글)을 쓴다.
data class PageChromeView
    // quality-exception: 페이지 템플릿의 메타데이터·신뢰된 자산·머리글/바닥글·RSS 등 10개 필드를 보존하는 뷰 계약이다.
    @Suppress("LongParameterList")
    constructor(
        val language: String,
        val siteName: String,
        val description: String?,
        val faviconHref: String?,
        val profile: PresentationProfileView,
        val styleSheets: List<PresentationAssetView>,
        val scripts: List<PresentationAssetView>,
        val header: PostDocumentView?,
        val footer: PostDocumentView?,
        val feedUrl: String? = null,
    )

// series는 시리즈에 속하고 공개 글이 있는 글에만 있다. 관리 미리보기는 공개 탐색을 계산하지 않아 null이다.
data class PostPageView(
    val chrome: PageChromeView,
    val title: String,
    val properties: PostPropertiesView,
    val post: PostDocumentView,
    val series: SeriesNavView? = null,
)

// categories는 앱 태그다. Notion 원문 태그는 공개 글 화면에 그리지 않는다.
data class PostPropertiesView(
    val summary: String?,
    val categories: List<CategoryLinkView>,
    val publishedOn: String?,
    val coverHref: String?,
)

data class HomeTabView(
    val label: String,
    val href: String,
    val current: Boolean,
)

// query는 입력한 그대로이고, error가 있으면 검색을 적용하지 않은 것이다. categoryId는 검색 후에도 태그를 유지하는 숨은 입력이다.
data class HomeSearchView(
    val query: String,
    val categoryId: String?,
    val error: String?,
    val resultLabel: String?,
)

data class ActionLinkView(
    val label: String,
    val href: String,
)

data class EmptyStateView(
    val message: String,
    val actions: List<ActionLinkView>,
)

// layout은 CSS 클래스 접미사(plain·thumbnails·cards)다. empty는 목록이 비었을 때만 있다.
data class PostListView(
    val entries: List<PostListEntryView>,
    val pagination: PaginationView,
    val layout: String,
    val empty: EmptyStateView?,
)

// categories는 보여 줄 태그가 없거나 숨김 설정이면 null이다. tabs는 글·시리즈 전환이다.
data class HomePageView(
    val chrome: PageChromeView,
    val tabs: List<HomeTabView>,
    val search: HomeSearchView,
    val categories: CategoryNavView?,
    val list: PostListView,
)

// 설정에서 숨긴 항목(요약·날짜·분류)은 null 또는 빈 목록으로 전달해 템플릿이 조건 없이 그리게 한다.
data class PostListEntryView(
    val href: String,
    val title: String,
    val summary: String?,
    val publishedOn: String?,
    val categories: List<CategoryLinkView>,
    val thumbnailHref: String?,
)

data class CategoryLinkView(
    val name: String,
    val href: String,
    val count: Int?,
    val selected: Boolean,
)

// allCount는 태그와 검색에 상관없는 공개 글 수다. 글마다 태그가 여럿일 수 있어 태그별 개수의 합과 다를 수 있다.
data class CategoryNavView(
    val allHref: String,
    val allSelected: Boolean,
    val allCount: Int,
    val items: List<CategoryLinkView>,
)

data class PaginationView(
    val page: Int,
    val totalPages: Int,
    val previousHref: String?,
    val nextHref: String?,
)

data class PostDocumentView(
    val title: String,
    val blocks: List<BlockView>,
)

data class PresentationProfileView(
    val classes: List<String>,
)

data class PresentationAssetView(
    val publicPath: String,
    val mediaType: String,
    val integrity: String,
)
