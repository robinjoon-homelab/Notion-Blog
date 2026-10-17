package xyz.robinjoon.notionblog.blog.adapter.inbound.web.view

// 공개 글·목록·관리 미리보기가 같은 사이트 틀(메타데이터, 신뢰된 자산, 머리글·바닥글)을 쓴다.
data class PageChromeView
    // quality-exception: 페이지 템플릿의 메타데이터·신뢰된 자산·머리글/바닥글·RSS·기존 루트 링크 등 11개 필드를 보존하는 뷰 계약이다.
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
        val legacyRootHref: String? = null,
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

data class HomePageView(
    val chrome: PageChromeView,
    val entries: List<PostListEntryView>,
    val pagination: PaginationView,
)

data class PostListEntryView(
    val href: String,
    val title: String,
    val summary: String?,
    val tags: List<String>,
    val publishedOn: String,
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
