package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.web.util.UriComponentsBuilder
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.CategoryLinkView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.CategoryNavView
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
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PostListPage
import xyz.robinjoon.notionblog.blog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.blog.application.model.PublicPostPage
import xyz.robinjoon.notionblog.blog.application.model.SiteChrome
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration
import xyz.robinjoon.notionblog.blog.domain.home.HomeLayout
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
import java.util.Optional

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
        val locale = Locale.forLanguageTag(page.chrome.site.metadata.languageTag)
        val mappers = mappers(page.references, PUBLIC_MEDIA_PATH)
        val entries = page.entries
        val selected = page.selectedCategoryId
        return HomePageView(
            chrome = chromeView(page.chrome, mappers),
            entries = entries.items.map { entryView(it, page.configuration, locale, mappers(it.revisionId)) },
            pagination =
                PaginationView(
                    entries.number,
                    entries.totalPages,
                    homeHref(entries.number - 1, selected).takeIf { entries.hasPrevious },
                    homeHref(entries.number + 1, selected).takeIf { entries.hasNext },
                ),
            layout = layoutClass(page.configuration.layout),
            categories = categoryNav(page),
            emptyMessage = if (selected == null) "아직 공개된 글이 없습니다." else "이 카테고리에는 아직 공개된 글이 없습니다.",
        )
    }

    // 요약·날짜·분류는 설정에서 숨기면 값을 비워 템플릿이 그리지 않게 한다. 홈에는 Notion 태그 대신 앱 카테고리만 보인다.
    private fun entryView(
        entry: PostListEntry,
        configuration: HomeConfiguration,
        locale: Locale,
        mapper: PostContentViewMapper,
    ): PostListEntryView {
        val details = configuration.details
        return PostListEntryView(
            href = "/posts/${entry.postId.value}",
            title = entry.properties.title,
            summary = entry.properties.summary.takeIf { details.showSummary },
            publishedOn = date(entry.firstPublishedAt, locale).takeIf { details.showDate },
            categories = categoryBadges(entry).takeIf { details.showCategories }.orEmpty(),
            thumbnailHref = thumbnail(entry, mapper).takeIf { configuration.layout != HomeLayout.LIST },
        )
    }

    private fun categoryBadges(entry: PostListEntry): List<CategoryLinkView> =
        entry.categories.map { CategoryLinkView(it.name, categoryHref(it.id), null, false) }

    // 보관된 사본이 있는 커버만 쓴다. 원격 주소나 사본이 없는 커버는 기본 이미지로 대신해 외부 호출과 서명 URL 노출을 피한다.
    private fun thumbnail(
        entry: PostListEntry,
        mapper: PostContentViewMapper,
    ): String = (entry.properties.cover as? MediaSource.SourceHosted)?.let(mapper::mediaUrl) ?: DEFAULT_THUMBNAIL

    private fun categoryNav(page: PostListPage): CategoryNavView? {
        if (!page.configuration.details.showCategories || page.categories.isEmpty()) return null
        val selected = page.selectedCategoryId
        return CategoryNavView(
            allHref = "/",
            allSelected = selected == null,
            items =
                page.categories.map {
                    CategoryLinkView(it.category.name, categoryHref(it.category.id), it.count, it.category.id == selected)
                },
        )
    }

    private fun layoutClass(layout: HomeLayout): String =
        when (layout) {
            HomeLayout.LIST -> "plain"
            HomeLayout.THUMBNAIL_LIST -> "thumbnails"
            HomeLayout.CARDS -> "cards"
        }

    // 파일은 그리는 리비전 기준으로만 찾으므로 본문·머리글·바닥글마다 자기 리비전의 매퍼를 만든다.
    private fun mappers(
        references: ContentReferences,
        mediaPath: String,
    ): (PostRevisionId) -> PostContentViewMapper = { revisionId -> PostContentViewMapper(references, revisionId, mediaPath) }

    private fun categoryHref(id: CategoryId): String = homeHref(1, id)

    private fun homeHref(
        page: Int,
        categoryId: CategoryId?,
    ): String =
        UriComponentsBuilder
            .fromPath("/")
            .queryParamIfPresent("category", Optional.ofNullable(categoryId?.value?.toString()))
            .queryParamIfPresent("page", Optional.ofNullable(page.takeIf { it > 1 }))
            .build()
            .encode()
            .toUriString()

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
        const val DEFAULT_THUMBNAIL = "/ui/default-post-thumbnail.svg"
        val styleSheetMediaTypes = setOf("text/css")
        val scriptMediaTypes = setOf("application/javascript", "text/javascript")
        val faviconMediaTypes = setOf("image/x-icon", "image/png", "image/svg+xml", "image/webp")
    }
}
