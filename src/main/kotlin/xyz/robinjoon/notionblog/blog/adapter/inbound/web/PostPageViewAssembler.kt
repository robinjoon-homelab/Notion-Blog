package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.CategoryLinkView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PageChromeView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PostDocumentView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PostPageView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PostPropertiesView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PresentationAssetView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PresentationProfileView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.SeriesNavEntryView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.SeriesNavView
import xyz.robinjoon.notionblog.blog.application.model.AdminPostPreview
import xyz.robinjoon.notionblog.blog.application.model.ContentReferences
import xyz.robinjoon.notionblog.blog.application.model.CurrentClassification
import xyz.robinjoon.notionblog.blog.application.model.ListedCategory
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.blog.application.model.PublicPostPage
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesNavigation
import xyz.robinjoon.notionblog.blog.application.model.SitePresentationContext
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class PostPageViewAssembler(
    private val clock: Clock,
    private val feedUrl: String? = null,
) {
    fun assemble(page: PublicPostPage): PostPageView {
        val view = postView(page.chrome, page.post.revision, page.post.firstPublishedAt, mappers(page.references, PUBLIC_MEDIA_PATH))
        return view.copy(
            properties = view.properties.copy(categories = categoryLinks(page.categories)),
            series = page.series?.let(::seriesNav),
        )
    }

    // 관리자 미리보기는 공개본과 같은 렌더링을 쓰되 파일은 공개 여부와 무관한 관리자 경로에서 읽는다.
    fun assemble(
        preview: AdminPostPreview,
        current: CurrentClassification,
    ): PostPageView {
        val view =
            postView(preview.chrome, preview.revision, preview.history.firstPublishedAt, mappers(preview.references, ADMIN_MEDIA_PATH))
        return view.copy(properties = view.properties.copy(categories = categoryLinks(current.categories)))
    }

    internal fun publicChrome(
        chrome: SitePresentationContext,
        references: ContentReferences,
    ): PageChromeView = chromeView(chrome, mappers(references, PUBLIC_MEDIA_PATH))

    internal fun locale(chrome: SitePresentationContext): Locale = Locale.forLanguageTag(chrome.site.metadata.languageTag)

    internal fun date(
        instant: Instant,
        locale: Locale,
    ): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(instant.atZone(clock.zone))

    // 보관된 사본이 있는 커버만 쓴다. 원격 주소나 사본이 없는 커버는 기본 이미지로 대신해 외부 호출과 서명 URL 노출을 피한다.
    internal fun thumbnail(
        entry: PostListEntry,
        references: ContentReferences,
    ): String {
        val mapper = mappers(references, PUBLIC_MEDIA_PATH)(entry.revisionId)
        return (entry.properties.cover as? MediaSource.SourceHosted)?.let(mapper::mediaUrl) ?: DEFAULT_THUMBNAIL
    }

    private fun categoryLinks(categories: List<ListedCategory>): List<CategoryLinkView> =
        categories.map { CategoryLinkView(it.name, HomeLinks.category(it.id), null, false) }

    // 순번과 이전·다음은 공개 글만 담은 목록의 위치에서 얻는다.
    private fun seriesNav(navigation: PublicSeriesNavigation): SeriesNavView {
        val entries =
            navigation.posts.mapIndexed { index, link ->
                SeriesNavEntryView(index + 1, HomeLinks.post(link.postId), link.title, link.postId == navigation.currentPostId)
            }
        val index = navigation.currentIndex
        return SeriesNavView(
            title = navigation.title,
            href = HomeLinks.series(navigation.seriesId),
            position = index + 1,
            entries = entries,
            previous = entries.getOrNull(index - 1),
            next = entries.getOrNull(index + 1),
        )
    }

    // 파일은 그리는 리비전 기준으로만 찾으므로 본문·머리글·바닥글마다 자기 리비전의 매퍼를 만든다.
    private fun mappers(
        references: ContentReferences,
        mediaPath: String,
    ): (PostRevisionId) -> PostContentViewMapper = { revisionId -> PostContentViewMapper(references, revisionId, mediaPath) }

    private fun postView(
        chrome: SitePresentationContext,
        revision: PostRevision,
        publishedAt: Instant?,
        mappers: (PostRevisionId) -> PostContentViewMapper,
    ): PostPageView {
        val locale = locale(chrome)
        val mapper = mappers(revision.id)
        return PostPageView(
            chrome = chromeView(chrome, mappers),
            title = revision.title,
            properties = propertiesView(revision.properties, publishedAt?.let { date(it, locale) }, mapper),
            post = PostDocumentViewAssembler(revision, mapper, "").assemble(),
        )
    }

    private fun propertiesView(
        properties: PostProperties,
        publishedOn: String?,
        mapper: PostContentViewMapper,
    ): PostPropertiesView = PostPropertiesView(properties.summary, emptyList(), publishedOn, properties.cover?.let(mapper::mediaUrl))

    private fun chromeView(
        chrome: SitePresentationContext,
        mappers: (PostRevisionId) -> PostContentViewMapper,
    ): PageChromeView {
        val metadata = chrome.site.metadata
        return PageChromeView(
            language = metadata.languageTag,
            siteName = metadata.siteName,
            description = metadata.defaultDescription,
            faviconHref = metadata.favicon?.let { assetView(chrome.presentationAssets, it, faviconMediaTypes)?.publicPath },
            profile = profileView(chrome),
            styleSheets =
                chrome.presentation.assets.styleSheets.mapNotNull {
                    assetView(
                        chrome.presentationAssets,
                        it,
                        styleSheetMediaTypes,
                    )
                },
            scripts =
                chrome.presentation.assets.scripts
                    .mapNotNull { assetView(chrome.presentationAssets, it, scriptMediaTypes) },
            header = chrome.header?.let { documentView(it, mappers(it.id), "header-${it.id.value}-") },
            footer = chrome.footer?.let { documentView(it, mappers(it.id), "footer-${it.id.value}-") },
            feedUrl = feedUrl,
        )
    }

    private fun documentView(
        revision: PostRevision,
        mapper: PostContentViewMapper,
        idPrefix: String,
    ): PostDocumentView = PostDocumentViewAssembler(revision, mapper, idPrefix).assemble()

    private fun profileView(chrome: SitePresentationContext): PresentationProfileView =
        PresentationProfileView(
            listOf(
                "notion-color-mode-${chrome.presentation.tokens.colorMode.name.lowercase()}",
                "notion-content-width-${chrome.presentation.tokens.contentWidth.name.lowercase()}",
                "notion-density-${chrome.presentation.tokens.density.name.lowercase()}",
            ),
        )

    private fun assetView(
        assets: Map<PresentationAssetRef, PresentationAssetDescriptor>,
        reference: PresentationAssetRef,
        expectedMediaTypes: Set<String>,
    ): PresentationAssetView? {
        val descriptor = assets[reference] ?: return null
        if (descriptor.integrity != reference.integrity || descriptor.mediaType.lowercase() !in expectedMediaTypes ||
            !descriptor.publicPath.safeAssetPath()
        ) {
            return null
        }
        return PresentationAssetView(descriptor.publicPath, descriptor.mediaType, descriptor.integrity)
    }

    private fun String.safeAssetPath(): Boolean =
        startsWith('/') && !startsWith("//") && !contains('\\') && !contains('\r') && !contains('\n')

    private companion object {
        const val PUBLIC_MEDIA_PATH = "/media/"
        const val ADMIN_MEDIA_PATH = "/admin/media/"
        const val DEFAULT_THUMBNAIL = "/ui/default-post-thumbnail.svg"
        val styleSheetMediaTypes = setOf("text/css")
        val scriptMediaTypes = setOf("application/javascript", "text/javascript")
        val faviconMediaTypes = setOf("image/x-icon", "image/png", "image/svg+xml", "image/webp")
    }
}
