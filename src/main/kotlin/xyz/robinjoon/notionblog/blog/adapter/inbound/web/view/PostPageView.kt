package xyz.robinjoon.notionblog.blog.adapter.inbound.web.view

data class PostPageView
    // quality-exception: 페이지 템플릿의 메타데이터·신뢰된 자산·본문과 레이아웃·RSS·검색 메타 등 15개 필드를 보존하는 뷰 계약이다.
    @Suppress("LongParameterList")
    constructor(
        val language: String,
        val siteName: String,
        val title: String,
        val description: String?,
        val faviconHref: String?,
        val profile: PresentationProfileView,
        val styleSheets: List<PresentationAssetView>,
        val scripts: List<PresentationAssetView>,
        val header: PostDocumentView?,
        val post: PostDocumentView,
        val footer: PostDocumentView?,
        val feedUrl: String?,
        val canonicalUrl: String?,
        val openGraph: OpenGraphView,
        val jsonLd: String,
    )

// Open Graph values that are not already page fields; the template reuses the page title, site name, and description.
data class OpenGraphView(
    val type: String,
    val locale: String,
    val url: String?,
    val publishedTime: String?,
    val modifiedTime: String?,
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
