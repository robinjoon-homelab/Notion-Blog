package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.HomePageView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PageChromeView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PaginationView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PostDocumentView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PostListEntryView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PostPageView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PostPropertiesView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PresentationAssetView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PresentationProfileView
import xyz.robinjoon.notionblog.blog.application.model.AdminPostPreview
import xyz.robinjoon.notionblog.blog.application.model.ContentReferences
import xyz.robinjoon.notionblog.blog.application.model.PostListPage
import xyz.robinjoon.notionblog.blog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.blog.application.model.PublicPostPage
import xyz.robinjoon.notionblog.blog.application.model.SiteChrome
import xyz.robinjoon.notionblog.blog.domain.post.PostProperties
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
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
    fun assemble(page: PublicPostPage): PostPageView =
        postView(page.chrome, page.post.revision, page.post.firstPublishedAt, mappers(page.references, PUBLIC_MEDIA_PATH))

    // 관리자 미리보기는 공개본과 같은 렌더링을 쓰되 파일은 공개 여부와 무관한 관리자 경로에서 읽는다.
    fun assemble(preview: AdminPostPreview): PostPageView =
        postView(preview.chrome, preview.revision, preview.history.firstPublishedAt, mappers(preview.references, ADMIN_MEDIA_PATH))

    fun assemble(page: PostListPage): HomePageView {
        val entries = page.entries
        val locale = Locale.forLanguageTag(page.chrome.site.metadata.languageTag)
        return HomePageView(
            chrome = chromeView(page.chrome, mappers(page.references, PUBLIC_MEDIA_PATH)),
            entries =
                entries.items.map { entry ->
                    PostListEntryView(
                        href = "/posts/${entry.postId.value}",
                        title = entry.properties.title,
                        summary = entry.properties.summary,
                        tags = entry.properties.tags,
                        publishedOn = date(entry.firstPublishedAt, locale),
                    )
                },
            pagination =
                PaginationView(
                    entries.number,
                    entries.totalPages,
                    homeHref(entries.number - 1).takeIf { entries.hasPrevious },
                    homeHref(entries.number + 1).takeIf { entries.hasNext },
                ),
        )
    }

    // 파일은 그리는 리비전 기준으로만 찾으므로 본문·머리글·바닥글마다 자기 리비전의 매퍼를 만든다.
    private fun mappers(
        references: ContentReferences,
        mediaPath: String,
    ): (PostRevisionId) -> PostContentViewMapper = { revisionId -> PostContentViewMapper(references, revisionId, mediaPath) }

    private fun homeHref(page: Int): String = if (page == 1) "/" else "/?page=$page"

    private fun postView(
        chrome: SiteChrome,
        revision: PostRevision,
        publishedAt: Instant?,
        mappers: (PostRevisionId) -> PostContentViewMapper,
    ): PostPageView {
        val locale = Locale.forLanguageTag(chrome.site.metadata.languageTag)
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
    ): PostPropertiesView = PostPropertiesView(properties.summary, properties.tags, publishedOn, properties.cover?.let(mapper::mediaUrl))

    private fun chromeView(
        chrome: SiteChrome,
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
            legacyRootHref = chrome.rootPostId?.let { "/posts/${it.value}" },
        )
    }

    private fun documentView(
        revision: PostRevision,
        mapper: PostContentViewMapper,
        idPrefix: String,
    ): PostDocumentView = PostDocumentViewAssembler(revision, mapper, idPrefix).assemble()

    private fun date(
        instant: Instant,
        locale: Locale,
    ): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(instant.atZone(clock.zone))

    private fun profileView(chrome: SiteChrome): PresentationProfileView =
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
        val styleSheetMediaTypes = setOf("text/css")
        val scriptMediaTypes = setOf("application/javascript", "text/javascript")
        val faviconMediaTypes = setOf("image/x-icon", "image/png", "image/svg+xml", "image/webp")
    }
}
