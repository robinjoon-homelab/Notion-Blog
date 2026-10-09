package xyz.robinjoon.notionblog.adapter.inbound.web.view

data class PostPageView
    // quality-exception: 페이지 템플릿의 메타데이터·신뢰된 자산·본문과 레이아웃·RSS 등 12개 필드를 보존하는 뷰 계약이다.
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
        val feedUrl: String? = null,
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
