package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.ActionLinkView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.CategoryLinkView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.CategoryNavView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.EmptyStateView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.HomePageView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.HomeSearchView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PaginationView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PostListEntryView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.PostListView
import xyz.robinjoon.notionblog.blog.application.model.PostListEntry
import xyz.robinjoon.notionblog.blog.application.model.PostListPage
import xyz.robinjoon.notionblog.blog.domain.home.HomeLayout
import java.util.Locale

// 내 블로그 홈(글 탭)의 목록·태그 탐색·검색을 화면 모델로 만든다. 태그와 검색어는 쪽 이동 링크에도 유지된다.
class HomePageViewAssembler(
    private val pages: PostPageViewAssembler,
) {
    internal fun assemble(
        page: PostListPage,
        input: HomeSearchInput,
    ): HomePageView =
        HomePageView(
            chrome = pages.publicChrome(page.chrome, page.references),
            tabs = homeTabs(seriesCurrent = false),
            search = searchView(page, input),
            categories = categoryNav(page),
            list = listView(page),
        )

    private fun searchView(
        page: PostListPage,
        input: HomeSearchInput,
    ): HomeSearchView =
        HomeSearchView(
            query = input.text,
            categoryId = page.selectedCategoryId?.value?.toString(),
            error = if (input.tooLong) TOO_LONG_MESSAGE else null,
            resultLabel = page.search?.let { "검색 결과 ${page.entries.totalItems}개" },
        )

    private fun listView(page: PostListPage): PostListView {
        val entries = page.entries
        val selected = page.selectedCategoryId
        val locale = pages.locale(page.chrome)
        return PostListView(
            entries = entries.items.map { entryView(it, page, locale) },
            pagination =
                PaginationView(
                    entries.number,
                    entries.totalPages,
                    HomeLinks.home(entries.number - 1, selected, page.search).takeIf { entries.hasPrevious },
                    HomeLinks.home(entries.number + 1, selected, page.search).takeIf { entries.hasNext },
                ),
            layout = layoutClass(page.configuration.layout),
            empty = emptyState(page).takeIf { entries.items.isEmpty() },
        )
    }

    // 요약·날짜·태그는 설정에서 숨기면 값을 비워 템플릿이 그리지 않게 한다. 홈에는 Notion 태그 대신 앱 태그만 보인다.
    private fun entryView(
        entry: PostListEntry,
        page: PostListPage,
        locale: Locale,
    ): PostListEntryView {
        val details = page.configuration.details
        return PostListEntryView(
            href = HomeLinks.post(entry.postId),
            title = entry.properties.title,
            summary = entry.properties.summary.takeIf { details.showSummary },
            publishedOn = pages.date(entry.firstPublishedAt, locale).takeIf { details.showDate },
            categories =
                entry.categories
                    .map {
                        CategoryLinkView(it.name, HomeLinks.category(it.id), null, false)
                    }.takeIf { details.showCategories }
                    .orEmpty(),
            thumbnailHref = pages.thumbnail(entry, page.references).takeIf { page.configuration.layout != HomeLayout.LIST },
        )
    }

    // 태그 탐색은 표시 설정이 켜져 있고 공개 태그가 있을 때만 그린다. 선택한 검색어는 태그를 바꿔도 유지한다.
    private fun categoryNav(page: PostListPage): CategoryNavView? {
        if (!page.configuration.details.showCategories || page.categories.isEmpty()) return null
        val selected = page.selectedCategoryId
        return CategoryNavView(
            allHref = HomeLinks.home(1, null, page.search),
            allSelected = selected == null,
            allCount = page.totalPublished,
            items =
                page.categories.map {
                    CategoryLinkView(it.category.name, HomeLinks.home(1, it.category.id, page.search), it.count, it.category.id == selected)
                },
        )
    }

    // 조건을 풀거나 전체 글로 돌아가는 길을 항상 함께 준다.
    private fun emptyState(page: PostListPage): EmptyStateView {
        val message =
            when {
                page.search != null -> "조건에 맞는 공개 글이 없습니다."
                page.selectedCategoryId != null -> "이 태그에는 아직 공개된 글이 없습니다."
                else -> "아직 공개된 글이 없습니다."
            }
        val actions =
            buildList {
                if (page.search != null && page.selectedCategoryId != null) {
                    add(ActionLinkView("검색어 지우기", HomeLinks.home(1, page.selectedCategoryId, null)))
                }
                if (page.search != null || page.selectedCategoryId != null) add(ActionLinkView("전체 글 보기", "/"))
            }
        return EmptyStateView(message, actions)
    }

    private fun layoutClass(layout: HomeLayout): String =
        when (layout) {
            HomeLayout.LIST -> "plain"
            HomeLayout.THUMBNAIL_LIST -> "thumbnails"
            HomeLayout.CARDS -> "cards"
        }

    private companion object {
        const val TOO_LONG_MESSAGE = "검색어는 100자 이하로 입력하세요. 검색을 적용하지 않고 목록을 보여 드립니다."
    }
}
