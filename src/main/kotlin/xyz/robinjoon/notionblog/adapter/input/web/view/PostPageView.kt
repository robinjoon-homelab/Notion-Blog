package xyz.robinjoon.notionblog.adapter.input.web.view

data class PostPageView(
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
