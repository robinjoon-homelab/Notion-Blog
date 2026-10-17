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

data class PostPageView(
    val chrome: PageChromeView,
    val title: String,
    val properties: PostPropertiesView,
    val post: PostDocumentView,
)

data class PostPropertiesView(
    val summary: String?,
    val tags: List<String>,
    val publishedOn: String?,
    val coverHref: String?,
)

// layout은 CSS 클래스 접미사(plain·thumbnails·cards)다. categories는 보여 줄 분류가 없거나 숨김 설정이면 null이다.
data class HomePageView(
    val chrome: PageChromeView,
    val entries: List<PostListEntryView>,
    val pagination: PaginationView,
    val layout: String,
    val categories: CategoryNavView?,
    val emptyMessage: String,
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

data class CategoryNavView(
    val allHref: String,
    val allSelected: Boolean,
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
